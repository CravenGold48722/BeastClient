/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.autocomplete;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class ModelSettings
{
	public final TextFieldSetting apiKey = new TextFieldSetting("API key",
		"Your OpenRouter API key. You can create one at"
			+ " openrouter.ai/settings/keys.\n\n"
			+ "Leave this blank to use the "
			+ OpenRouterMessageCompleter.API_KEY_ENV_VAR
			+ " environment variable instead.\n\n"
			+ "§cWarning:§r A key entered here is saved in plain text"
			+ " in your settings.json and is shared with anyone you send that"
			+ " file to.",
		"", true);
	
	public final EnumSetting<OpenRouterModel> openRouterModel =
		new EnumSetting<>("OpenRouter model",
			"The model to use for OpenRouter API calls.",
			OpenRouterModel.values(), OpenRouterModel.GPT_4O_MINI);
	
	public enum OpenRouterModel
	{
		GPT_4O_MINI("openai/gpt-4o-mini"),
		GPT_4O("openai/gpt-4o"),
		CLAUDE_3_5_HAIKU("anthropic/claude-3.5-haiku"),
		CLAUDE_3_5_SONNET("anthropic/claude-3.5-sonnet"),
		GEMINI_FLASH("google/gemini-2.0-flash-001"),
		LLAMA_3_3_70B("meta-llama/llama-3.3-70b-instruct"),
		LLAMA_3_1_8B("meta-llama/llama-3.1-8b-instruct"),
		MISTRAL_NEMO("mistralai/mistral-nemo"),
		DEEPSEEK_CHAT("deepseek/deepseek-chat"),
		QWEN_2_5_72B("qwen/qwen-2.5-72b-instruct");
		
		private final String name;
		
		private OpenRouterModel(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public final SliderSetting maxTokens = new SliderSetting("Max tokens",
		"The maximum number of tokens that the model can generate.\n\n"
			+ "Higher values allow the model to predict longer chat messages,"
			+ " but also increase the time it takes to generate predictions.\n\n"
			+ "The default value of 16 is fine for most use cases.",
		16, 1, 100, 1, ValueDisplay.INTEGER);
	
	public final SliderSetting temperature = new SliderSetting("Temperature",
		"Controls the model's creativity and randomness. A higher value will"
			+ " result in more creative and sometimes nonsensical completions,"
			+ " while a lower value will result in more boring completions.",
		1, 0, 2, 0.01, ValueDisplay.DECIMAL);
	
	public final SliderSetting topP = new SliderSetting("Top P",
		"An alternative to temperature. Makes the model less random by only"
			+ " letting it choose from the most likely tokens.\n\n"
			+ "A value of 100% disables this feature by letting the model"
			+ " choose from all tokens.",
		1, 0, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	public final SliderSetting presencePenalty =
		new SliderSetting("Presence penalty",
			"Penalty for choosing tokens that already appear in the chat"
				+ " history.\n\n"
				+ "Positive values encourage the model to use synonyms and"
				+ " talk about different topics. Negative values encourage the"
				+ " model to repeat the same word over and over again.",
			0, -2, 3, 0.01, ValueDisplay.DECIMAL);
	
	public final SliderSetting frequencyPenalty =
		new SliderSetting("Frequency penalty",
			"Similar to presence penalty, but based on how often the token"
				+ " appears in the chat history.\n\n"
				+ "Positive values encourage the model to use synonyms and"
				+ " talk about different topics. Negative values encourage the"
				+ " model to repeat existing chat messages.",
			0, -2, 3, 0.01, ValueDisplay.DECIMAL);
	
	public final EnumSetting<StopSequence> stopSequence = new EnumSetting<>(
		"Stop sequence",
		"Controls how AutoComplete detects the end of a chat message.\n\n"
			+ "\u00a7lLine Break\u00a7r is the default value and is recommended"
			+ " for most language models.\n\n"
			+ "\u00a7lNext Message\u00a7r works better with certain"
			+ " code-optimized language models, which have a tendency to insert"
			+ " line breaks in the middle of a chat message.",
		StopSequence.values(), StopSequence.LINE_BREAK);
	
	public enum StopSequence
	{
		LINE_BREAK("Line Break", "\n"),
		NEXT_MESSAGE("Next Message", "\n<");
		
		private final String name;
		private final String sequence;
		
		private StopSequence(String name, String sequence)
		{
			this.name = name;
			this.sequence = sequence;
		}
		
		public String getSequence()
		{
			return sequence;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public final SliderSetting contextLength = new SliderSetting(
		"Context length",
		"Controls how many messages from the chat history are used to generate"
			+ " predictions.\n\n"
			+ "Higher values improve the quality of predictions, but also"
			+ " increase the time it takes to generate them, as well as cost"
			+ " (for APIs like OpenRouter) or RAM usage (for self-hosted"
			+ " models).",
		10, 0, 100, 1, ValueDisplay.INTEGER);
	
	public final CheckboxSetting filterServerMessages =
		new CheckboxSetting("Filter server messages",
			"Only shows player-made chat messages to the model.\n\n"
				+ "This can help you save tokens and get more out of a low"
				+ " context length, but it also means that the model will have"
				+ " no idea about events like players joining, leaving, dying,"
				+ " etc.",
			false);
	
	public final TextFieldSetting customModel = new TextFieldSetting(
		"Custom model",
		"If set, this model will be used instead of the one specified in the"
			+ " \"OpenRouter model\" setting.\n\n"
			+ "Use this for any other model slug that OpenRouter offers, or if"
			+ " you are using a custom endpoint that is OpenAI-compatible but"
			+ " offers different models.",
		"");
	
	public final EnumSetting<CustomModelType> customModelType =
		new EnumSetting<>("Custom model type", "Whether the custom"
			+ " model should use the chat endpoint or the legacy endpoint.\n\n"
			+ "If \"Custom model\" is left blank, this setting is ignored.",
			CustomModelType.values(), CustomModelType.CHAT);
	
	public enum CustomModelType
	{
		CHAT("Chat", true),
		LEGACY("Legacy", false);
		
		private final String name;
		private final boolean chat;
		
		private CustomModelType(String name, boolean chat)
		{
			this.name = name;
			this.chat = chat;
		}
		
		public boolean isChat()
		{
			return chat;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public final TextFieldSetting openRouterChatEndpoint =
		new TextFieldSetting("OpenRouter chat endpoint",
			"Endpoint for OpenRouter's chat completion API.",
			"https://openrouter.ai/api/v1/chat/completions");
	
	public final TextFieldSetting openRouterLegacyEndpoint =
		new TextFieldSetting("OpenRouter legacy endpoint",
			"Endpoint for OpenRouter's legacy completion API.",
			"https://openrouter.ai/api/v1/completions");
	
	private final List<Setting> settings =
		Collections.unmodifiableList(Arrays.asList(apiKey, openRouterModel,
			maxTokens, temperature, topP, presencePenalty, frequencyPenalty,
			stopSequence, contextLength, filterServerMessages, customModel,
			customModelType, openRouterChatEndpoint, openRouterLegacyEndpoint));
	
	public void forEach(Consumer<Setting> action)
	{
		settings.forEach(action);
	}
	
	/**
	 * @return the "API key" setting if it's set, otherwise the
	 *         WURST_OPENROUTER_KEY environment variable, or an empty string if
	 *         neither is set.
	 */
	public String getApiKey()
	{
		String key = apiKey.getValue().trim();
		if(!key.isEmpty())
			return key;
		
		String envKey =
			System.getenv(OpenRouterMessageCompleter.API_KEY_ENV_VAR);
		return envKey == null ? "" : envKey.trim();
	}
	
	/**
	 * @return the "Custom model" setting if it's set, otherwise the selected
	 *         OpenRouter model.
	 */
	public String getModelName()
	{
		String custom = customModel.getValue();
		return custom.isBlank() ? "" + openRouterModel.getSelected() : custom;
	}
	
	/**
	 * @return true if the current model uses the chat endpoint. All of
	 *         OpenRouter's built-in models do; a custom model can be set to
	 *         "Legacy" instead.
	 */
	public boolean isChatModel()
	{
		if(customModel.getValue().isBlank())
			return true;
		
		return customModelType.getSelected().isChat();
	}
}
