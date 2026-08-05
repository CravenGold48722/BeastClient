/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.wurstclient.hacks.aimassist.AiStrategy.Dodge;
import net.wurstclient.util.json.JsonUtils;

/**
 * Talks to the Gemini API on a background thread and keeps the most recent
 * combat strategy available for the game thread to read.
 *
 * <p>
 * A request takes far longer than a tick, so this never blocks the game. At
 * most one request is in flight at a time; the game thread hands over a
 * snapshot whenever it likes and simply reads back whatever the last answer
 * was.
 */
public final class GeminiCombatAdvisor
{
	private static final String ENDPOINT =
		"https://generativelanguage.googleapis.com/v1beta/models/";
	
	private static final String SYSTEM_PROMPT = """
		You are the tactical brain of a Minecraft PvP combat assistant.
		
		You are given a snapshot of a fight: the player you control ("self")
		and the nearby opponents. Positions are in blocks, velocities are in
		blocks per tick (20 ticks = 1 second). "facing_us" is how directly an
		opponent is looking at us, from 0 (looking away) to 1 (looking straight
		at us). "closing_speed" is how fast they are approaching us; negative
		means they are running away.
		
		Decide the strategy the assistant should fight with. You do NOT press
		keys directly - a reflex layer running every tick handles the exact
		timing. You set the plan it follows.
		
		Guidance:
		- dodge_bias: which way to strafe when the reflex layer decides to
		dodge. Prefer strafing toward the opponent's off-hand side and away
		from where their swing is coming from. Alternate over time so you do
		not become predictable.
		- dodge_ticks: how long to hold that strafe. 3-6 for a quick juke,
		7-12 to reposition.
		- aggression: 0 means dodge at the first hint of danger, 1 means stay
		glued to the target and only dodge when a hit is imminent. Lower it
		when our health is low or we are outnumbered; raise it when the
		opponent is weak or fleeing.
		- target_id: the entity id of the opponent worth fighting. Switch to
		whoever is actually threatening us: if the current target is running
		away and someone else is closing in and looking at us, pick that
		someone else. Return -1 to stay on the current target.
		- disengage: true only if we should back off entirely, e.g. very low
		health against multiple healthy opponents.
		
		Reply with a raw JSON object only. No prose, and no Markdown code
		fence around it.""";
	
	private final AtomicReference<AiStrategy> strategy =
		new AtomicReference<>(AiStrategy.DEFAULT);
	private final AtomicReference<String> lastError =
		new AtomicReference<>(null);
	private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
	
	/**
	 * How long to stop sending after the API reports a rate limit. Hammering
	 * a quota that is already exhausted just burns more of it and fills chat
	 * with errors.
	 */
	private static final long RATE_LIMIT_BACKOFF_SECONDS = 60;
	
	private volatile Thread worker;
	private long lastRequestNanos;
	private volatile long backoffUntilNanos;
	
	/**
	 * Hands a fresh snapshot to the model, unless a request is already in
	 * flight or the minimum interval hasn't elapsed yet. Returns immediately -
	 * safe to call every tick from the game thread.
	 *
	 * @param apiKey
	 *            the user's Gemini API key
	 * @param model
	 *            model name, e.g. {@code gemini-2.0-flash}
	 * @param intervalMs
	 *            minimum time between requests
	 * @param snapshot
	 *            the fight state, already serialized on the game thread
	 */
	public void request(String apiKey, String model, long intervalMs,
		JsonObject snapshot)
	{
		if(apiKey == null || apiKey.isBlank() || !isDueForRequest(intervalMs))
			return;
			
		// Only one conversation at a time - a queue would just build up advice
		// about a fight that has already moved on.
		if(!requestInFlight.compareAndSet(false, true))
			return;
		
		lastRequestNanos = System.nanoTime();
		
		Thread thread = new Thread(() -> {
			try
			{
				AiStrategy result = call(apiKey, model, snapshot);
				if(result != null)
				{
					strategy.set(result);
					lastError.set(null);
				}
				
			}catch(Exception e)
			{
				lastError.set(describe(e));
				
			}finally
			{
				requestInFlight.set(false);
			}
		}, "beast-aimassist-gemini");
		
		thread.setDaemon(true);
		worker = thread;
		thread.start();
	}
	
	/**
	 * Whether a new request would actually be sent right now.
	 *
	 * <p>
	 * Callers check this <i>before</i> building a snapshot - assembling the
	 * fight state is pure waste on the ~19 ticks out of 20 where nothing is
	 * going to be sent.
	 */
	public boolean isDueForRequest(long intervalMs)
	{
		long now = System.nanoTime();
		
		if(now < backoffUntilNanos)
			return false;
		
		return !requestInFlight.get()
			&& now - lastRequestNanos >= intervalMs * 1_000_000L;
	}
	
	private AiStrategy call(String apiKey, String model, JsonObject snapshot)
		throws IOException
	{
		URL url = URI.create(ENDPOINT + model + ":generateContent?key="
			+ URLEncoder.encode(apiKey, StandardCharsets.UTF_8)).toURL();
		
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Accept", "application/json");
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(15000);
		conn.setDoOutput(true);
		
		byte[] body = JsonUtils.GSON.toJson(buildRequest(snapshot))
			.getBytes(StandardCharsets.UTF_8);
		
		try(OutputStream os = conn.getOutputStream())
		{
			os.write(body);
			os.flush();
		}
		
		int status = conn.getResponseCode();
		if(status < 200 || status >= 300)
		{
			String detail = describeApiError(readAll(conn.getErrorStream()));
			
			// 429 means the quota is gone. Keep asking and it only gets worse,
			// so stand down for a while.
			if(status == 429)
			{
				backoffUntilNanos = System.nanoTime()
					+ RATE_LIMIT_BACKOFF_SECONDS * 1_000_000_000L;
				
				throw new IOException("rate limit reached, pausing for "
					+ RATE_LIMIT_BACKOFF_SECONDS + "s."
					+ " Raise the 'AI interval' setting or check your Gemini"
					+ " quota."
					+ (detail.isEmpty() ? "" : " (" + detail + ")"));
			}
			
			throw new IOException(
				"HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
		}
		
		String response;
		try(InputStream in = conn.getInputStream())
		{
			response = readAll(in);
		}
		
		return parseStrategy(response);
	}
	
	private JsonObject buildRequest(JsonObject snapshot)
	{
		JsonObject root = new JsonObject();
		
		// system instruction
		JsonObject systemPart = new JsonObject();
		systemPart.addProperty("text", SYSTEM_PROMPT);
		JsonArray systemParts = new JsonArray();
		systemParts.add(systemPart);
		JsonObject systemInstruction = new JsonObject();
		systemInstruction.add("parts", systemParts);
		root.add("systemInstruction", systemInstruction);
		
		// user content: the fight snapshot
		JsonObject userPart = new JsonObject();
		userPart.addProperty("text", JsonUtils.GSON.toJson(snapshot));
		JsonArray userParts = new JsonArray();
		userParts.add(userPart);
		JsonObject content = new JsonObject();
		content.addProperty("role", "user");
		content.add("parts", userParts);
		JsonArray contents = new JsonArray();
		contents.add(content);
		root.add("contents", contents);
		
		// Structured output, so the reply is guaranteed-shaped JSON rather
		// than prose we would have to scrape.
		JsonObject config = new JsonObject();
		config.addProperty("temperature", 0.4);
		config.addProperty("maxOutputTokens", 256);
		config.addProperty("responseMimeType", "application/json");
		config.add("responseSchema", buildSchema());
		root.add("generationConfig", config);
		
		return root;
	}
	
	private JsonObject buildSchema()
	{
		JsonObject properties = new JsonObject();
		
		JsonObject dodge = new JsonObject();
		dodge.addProperty("type", "STRING");
		JsonArray dodgeValues = new JsonArray();
		dodgeValues.add("left");
		dodgeValues.add("right");
		dodgeValues.add("none");
		dodge.add("enum", dodgeValues);
		properties.add("dodge_bias", dodge);
		
		properties.add("dodge_ticks", numberSchema("INTEGER"));
		properties.add("aggression", numberSchema("NUMBER"));
		properties.add("target_id", numberSchema("INTEGER"));
		
		JsonObject disengage = new JsonObject();
		disengage.addProperty("type", "BOOLEAN");
		properties.add("disengage", disengage);
		
		JsonObject reason = new JsonObject();
		reason.addProperty("type", "STRING");
		properties.add("reason", reason);
		
		JsonArray required = new JsonArray();
		required.add("dodge_bias");
		required.add("dodge_ticks");
		required.add("aggression");
		required.add("target_id");
		required.add("disengage");
		
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "OBJECT");
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}
	
	private JsonObject numberSchema(String type)
	{
		JsonObject obj = new JsonObject();
		obj.addProperty("type", type);
		return obj;
	}
	
	private AiStrategy parseStrategy(String response) throws IOException
	{
		JsonElement root;
		try
		{
			root = JsonParser.parseString(response);
			
		}catch(RuntimeException e)
		{
			throw new IOException(
				"couldn't parse the reply: " + trim(response, 200));
		}
		
		if(!root.isJsonObject())
			return null;
		
		JsonArray candidates =
			root.getAsJsonObject().getAsJsonArray("candidates");
		if(candidates == null || candidates.isEmpty())
			return null;
		
		JsonObject content =
			candidates.get(0).getAsJsonObject().getAsJsonObject("content");
		if(content == null)
			return null;
		
		JsonArray parts = content.getAsJsonArray("parts");
		if(parts == null || parts.isEmpty())
			return null;
		
		JsonElement textElement = parts.get(0).getAsJsonObject().get("text");
		if(textElement == null || !textElement.isJsonPrimitive())
			return null;
		
		String decisionText = stripCodeFence(textElement.getAsString());
		
		JsonElement decision;
		try
		{
			decision = JsonParser.parseString(decisionText);
			
		}catch(RuntimeException e)
		{
			throw new IOException(
				"the model didn't reply with JSON: " + trim(decisionText, 200));
		}
		
		if(!decision.isJsonObject())
			return null;
		
		JsonObject d = decision.getAsJsonObject();
		
		Dodge bias = Dodge.parse(optString(d, "dodge_bias"));
		int dodgeTicks = clamp(optInt(d, "dodge_ticks", 4), 1, 20);
		float aggression = (float)clamp(optDouble(d, "aggression", 0.5), 0, 1);
		int targetId = optInt(d, "target_id", -1);
		boolean disengage = optBoolean(d, "disengage");
		String reason = optString(d, "reason");
		
		return new AiStrategy(bias, dodgeTicks, aggression, targetId, disengage,
			reason == null ? "" : reason, System.nanoTime());
	}
	
	/** The most recent strategy, or the neutral default. */
	public AiStrategy getStrategy()
	{
		return strategy.get();
	}
	
	/** The last error message, or null if the most recent call succeeded. */
	public String getLastError()
	{
		return lastError.get();
	}
	
	public boolean isRequestInFlight()
	{
		return requestInFlight.get();
	}
	
	/**
	 * Drops any pending advice and lets the in-flight request finish into the
	 * void. Called when AimAssist is switched off.
	 */
	public void reset()
	{
		strategy.set(AiStrategy.DEFAULT);
		lastError.set(null);
		lastRequestNanos = 0L;
		backoffUntilNanos = 0L;
		
		Thread thread = worker;
		if(thread != null)
			thread.interrupt();
		worker = null;
	}
	
	// ── small helpers
	// ─────────────────────────────────────────────────────────
	
	private static String readAll(InputStream in) throws IOException
	{
		if(in == null)
			return "";
		
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}
	
	/**
	 * Pulls the human-readable message out of a Gemini error body, so chat
	 * gets "You exceeded your current quota" instead of 300 characters of raw
	 * JSON.
	 */
	private static String describeApiError(String body)
	{
		if(body == null || body.isBlank())
			return "";
		
		try
		{
			JsonElement root = JsonParser.parseString(body);
			if(root.isJsonObject())
			{
				JsonObject error =
					root.getAsJsonObject().getAsJsonObject("error");
				if(error != null)
				{
					String message = optString(error, "message");
					if(message != null && !message.isBlank())
						return trim(message, 160);
				}
			}
			
		}catch(RuntimeException e)
		{
			// Not JSON - fall through and show the raw body instead.
		}
		
		return trim(body, 160);
	}
	
	private static String trim(String s, int max)
	{
		String flat = s.replace('\n', ' ').replace('\r', ' ').strip();
		return flat.length() <= max ? flat : flat.substring(0, max) + "...";
	}
	
	/**
	 * Removes a Markdown code fence from around the model's reply.
	 *
	 * <p>
	 * The request asks for {@code application/json} with a response schema, but
	 * models still sometimes wrap the object in a
	 * <code>```json ... ```</code> block. That produced a parse failure at
	 * "line 1 column 7" - which is exactly the length of the opening fence.
	 */
	private static String stripCodeFence(String text)
	{
		String trimmed = text.strip();
		if(!trimmed.startsWith("```"))
			return trimmed;
		
		// Drop the opening fence, along with any language tag on that line.
		int newline = trimmed.indexOf('\n');
		String body =
			newline < 0 ? trimmed.substring(3) : trimmed.substring(newline + 1);
		
		int closing = body.lastIndexOf("```");
		if(closing >= 0)
			body = body.substring(0, closing);
		
		return body.strip();
	}
	
	private static String describe(Exception e)
	{
		String message = e.getMessage();
		return message == null || message.isBlank()
			? e.getClass().getSimpleName() : message;
	}
	
	private static String optString(JsonObject obj, String key)
	{
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
	
	private static int optInt(JsonObject obj, String key, int fallback)
	{
		JsonElement e = obj.get(key);
		try
		{
			return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
		}catch(NumberFormatException ex)
		{
			return fallback;
		}
	}
	
	private static double optDouble(JsonObject obj, String key, double fallback)
	{
		JsonElement e = obj.get(key);
		try
		{
			return e != null && e.isJsonPrimitive() ? e.getAsDouble()
				: fallback;
		}catch(NumberFormatException ex)
		{
			return fallback;
		}
	}
	
	private static boolean optBoolean(JsonObject obj, String key)
	{
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsBoolean();
	}
	
	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
	
	private static double clamp(double value, double min, double max)
	{
		return Math.max(min, Math.min(max, value));
	}
}
