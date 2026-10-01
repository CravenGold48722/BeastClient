/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm.RawImage;
import net.fabricmc.fabric.impl.client.gametest.screenshot.TestScreenshotComparisonAlgorithms.RawImageImpl;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandSourceStack;

public enum WurstClientTestHelper
{
	;
	
	/**
	 * Folder holding Beast's own screenshot templates, set by build.gradle.
	 * The Imgur templates that upstream Wurst uses show Wurst's branding and
	 * color scheme, so nothing in this fork matches them. A template stored
	 * here wins over the Imgur URL; the URL is only used for templates that
	 * haven't been re-recorded yet.
	 */
	private static final Path TEMPLATE_DIR =
		Optional.ofNullable(System.getProperty("wurst.test.templateDir"))
			.map(Path::of).orElse(null);
	
	/**
	 * Set by running the tests with {@code -PupdateScreenshots}. Instead of
	 * failing, every comparison then writes what it actually saw back to
	 * {@link #TEMPLATE_DIR}, keeping the old template's alpha mask so the
	 * ignored regions stay ignored.
	 */
	private static final boolean UPDATE_TEMPLATES =
		System.getProperty("wurst.test.updateTemplates") != null;
	
	/** Templates that only apply when the tests run with Sodium & co. */
	private static final String MODS_SUBDIR = "with_mods";
	
	/**
	 * Takes a screenshot, matches it against the template image, and throws if
	 * it doesn't match. This method allows the template image to have
	 * an alpha channel and ignores any pixels that are >50% transparent. This
	 * way, you can precisely control which parts of the screenshot to assert
	 * against the template and which parts to ignore.
	 */
	public static void assertScreenshotEquals(ClientGameTestContext context,
		String fileName, String templateUrl)
	{
		ThreadingImpl.checkOnGametestThread("assertScreenshotEquals");
		
		Path localTemplate = findTemplate(fileName);
		String templateName = localTemplate != null
			? localTemplate.getFileName().toString() : templateUrl;
		
		NativeImage nativeTemplateImage = localTemplate != null
			? loadImageFile(localTemplate) : downloadImage(templateUrl);
		boolean[][] mask = alphaChannelToMask(nativeTemplateImage);
		RawImage<int[]> rawTemplateImage =
			RawImageImpl.fromColorNativeImage(nativeTemplateImage);
		RawImage<int[]> maskedTemplateImage = applyMask(rawTemplateImage, mask);
		
		Path screenshotPath = context.takeScreenshot(fileName);
		RawImage<int[]> rawScreenshotImage =
			RawImageImpl.fromColorNativeImage(loadImageFile(screenshotPath));
		
		// The mask is the template's size, so a screenshot of a different size
		// can't even be compared against it.
		boolean sizeMatches =
			rawScreenshotImage.width() == maskedTemplateImage.width()
				&& rawScreenshotImage.height() == maskedTemplateImage.height();
		
		boolean matches = false;
		if(sizeMatches)
		{
			RawImage<int[]> maskedScreenshotImage =
				applyMask(rawScreenshotImage, mask);
			
			TestScreenshotComparisonAlgorithm algo =
				TestScreenshotComparisonAlgorithm.meanSquaredDifference(3e-4F);
			
			matches = algo.findColor(maskedScreenshotImage,
				maskedTemplateImage) != null;
		}
		
		// Only rewrite templates that actually changed, so that re-recording
		// doesn't churn every PNG in the repo.
		if(UPDATE_TEMPLATES && (!matches || localTemplate == null))
		{
			saveTemplate(fileName, screenshotPath, sizeMatches ? mask : null);
			return;
		}
		
		if(matches)
			return;
		
		if(!sizeMatches)
			throw new AssertionError(
				"Screenshot and template dimensions do not match");
		
		ghSummary("### Screenshot " + fileName + " does not match template");
		ghSummary("Expected:");
		if(localTemplate == null)
			ghSummary("![" + fileName + "_template](" + templateUrl + ")");
		else
			ghSummary("`" + localTemplate + "`");
		ghSummary("Actual:");
		String url = tryUploadToImgur(screenshotPath);
		if(url != null)
			ghSummary("![" + fileName + "](" + url + ")");
		else
			ghSummary("Couldn't upload " + fileName
				+ ".png to Imgur. Check the Test Screenshots.zip artifact.");
		
		throw new AssertionError("Screenshot '" + fileName
			+ "' does not match template '" + templateName
			+ "'. If the UI changed on purpose, re-record the templates with"
			+ " ./gradlew runClientGameTest -PupdateScreenshots");
	}
	
	/**
	 * Returns the local template for the given screenshot, or null if there
	 * isn't one. Mod compatibility runs get their own folder, but fall back to
	 * the normal templates for everything that Sodium & co. don't change.
	 */
	private static Path findTemplate(String fileName)
	{
		if(TEMPLATE_DIR == null)
			return null;
		
		if(WurstTest.IS_MOD_COMPAT_TEST)
		{
			Path withMods =
				TEMPLATE_DIR.resolve(MODS_SUBDIR).resolve(fileName + ".png");
			if(Files.exists(withMods))
				return withMods;
		}
		
		Path template = TEMPLATE_DIR.resolve(fileName + ".png");
		return Files.exists(template) ? template : null;
	}
	
	/**
	 * Writes the screenshot back out as the new template, with the old
	 * template's mask baked into the alpha channel. A null mask records the
	 * whole screenshot, with nothing ignored.
	 */
	private static void saveTemplate(String fileName, Path screenshotPath,
		boolean[][] mask)
	{
		Path folder = WurstTest.IS_MOD_COMPAT_TEST
			? TEMPLATE_DIR.resolve(MODS_SUBDIR) : TEMPLATE_DIR;
		Path templatePath = folder.resolve(fileName + ".png");
		
		try
		{
			BufferedImage screenshot = ImageIO.read(screenshotPath.toFile());
			int width = screenshot.getWidth();
			int height = screenshot.getHeight();
			
			// A mask from a differently sized template can't be reused, so
			// such a template is re-recorded without any ignored regions.
			boolean maskFits = mask != null && mask.length == width
				&& mask[0].length == height;
			
			BufferedImage template =
				new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			for(int y = 0; y < height; y++)
				for(int x = 0; x < width; x++)
				{
					int alpha = !maskFits || mask[x][y] ? 0xFF000000 : 0;
					template.setRGB(x, y,
						screenshot.getRGB(x, y) & 0xFFFFFF | alpha);
				}
			
			Files.createDirectories(folder);
			ImageIO.write(template, "png", templatePath.toFile());
			System.out.println("Updated screenshot template " + templatePath);
			
		}catch(IOException e)
		{
			throw new RuntimeException(
				"Couldn't write screenshot template " + templatePath, e);
		}
	}
	
	private static boolean[][] alphaChannelToMask(NativeImage template)
	{
		if(!template.format().hasAlpha())
		{
			int width = template.getWidth();
			int height = template.getHeight();
			boolean[][] mask = new boolean[width][height];
			for(int y = 0; y < height; y++)
				for(int x = 0; x < width; x++)
					mask[x][y] = false;
			return mask;
		}
		
		int width = template.getWidth();
		int height = template.getHeight();
		boolean[][] mask = new boolean[width][height];
		
		int size = width * height;
		int alphaOffset = template.format().alphaOffset() / 8;
		int channelCount = template.format().components();
		
		for(int i = 0; i < size; i++)
		{
			int x = i % width;
			int y = i / width;
			int alpha = MemoryUtil.memGetByte(
				template.getPointer() + i * channelCount + alphaOffset) & 0xff;
			mask[x][y] = alpha > 127;
		}
		
		return mask;
	}
	
	private static RawImage<int[]> applyMask(RawImage<int[]> image,
		boolean[][] mask)
	{
		int width = image.width();
		int height = image.height();
		int[] inData = image.data();
		int[] outData = new int[width * height];
		
		for(int y = 0; y < height; y++)
			for(int x = 0; x < width; x++)
				outData[y * width + x] = mask[x][y] ? inData[y * width + x] : 0;
			
		return new RawImageImpl<>(width, height, outData);
	}
	
	public static NativeImage loadImageFile(Path path)
	{
		try(InputStream inputStream = Files.newInputStream(path))
		{
			return NativeImage.read(inputStream);
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public static NativeImage downloadImage(String url)
	{
		try(InputStream inputStream = URI.create(url).toURL().openStream())
		{
			return NativeImage.read(inputStream);
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public static void hideSplashTexts(ClientGameTestContext context)
	{
		context.runOnClient(mc -> {
			mc.options.hideSplashTexts().set(true);
		});
	}
	
	/**
	 * Waits for the fading animation of the title screen to finish, or fails
	 * after 10 seconds.
	 */
	public static void waitForTitleScreenFade(ClientGameTestContext context)
	{
		context.waitFor(mc -> {
			if(!(mc.screen instanceof TitleScreen titleScreen))
				return false;
			
			return !titleScreen.fading;
		});
	}
	
	public static void runCommand(TestServerContext server, String command)
	{
		String commandWithPlayer = "execute as @p at @s run " + command;
		server.runOnServer(mc -> {
			ParseResults<CommandSourceStack> results =
				mc.getCommands().getDispatcher().parse(commandWithPlayer,
					mc.createCommandSourceStack());
			
			if(!results.getExceptions().isEmpty())
			{
				StringBuilder errors =
					new StringBuilder("Invalid command: /" + commandWithPlayer);
				for(CommandSyntaxException e : results.getExceptions().values())
					errors.append("\n").append(e.getMessage());
				
				throw new RuntimeException(errors.toString());
			}
			
			mc.getCommands().performCommand(results, commandWithPlayer);
		});
	}
	
	public static void runWurstCommand(ClientGameTestContext context,
		String command)
	{
		TestInput input = context.getInput();
		input.pressKey(GLFW.GLFW_KEY_T);
		input.typeChars("." + command);
		input.pressKey(GLFW.GLFW_KEY_ENTER);
	}
	
	public static void ghSummary(String s)
	{
		String summaryPath = System.getenv("GITHUB_STEP_SUMMARY");
		System.out.println(s);
		if(summaryPath == null)
			return;
		
		try
		{
			Files.write(Paths.get(summaryPath), (s + "\n").getBytes(),
				StandardOpenOption.APPEND);
			
		}catch(IOException e)
		{
			System.err.println("Couldn't write to GitHub step summary");
			e.printStackTrace();
		}
	}
	
	public static String tryUploadToImgur(Path imagePath)
	{
		String imgurClientId = System.getenv("IMGUR_CLIENT_ID");
		if(imgurClientId == null)
			return null;
		
		try
		{
			HttpClient client = HttpClient.newHttpClient();
			
			String boundary = UUID.randomUUID().toString();
			byte[] imageBytes = Files.readAllBytes(imagePath);
			String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
			
			String data = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"image\"\r\n\r\n"
				+ imageBase64 + "\r\n" + "--" + boundary + "--\r\n";
			
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://api.imgur.com/3/image"))
				.header("Authorization", "Client-ID " + imgurClientId)
				.header("Content-Type",
					"multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofString(data)).build();
			
			HttpResponse<String> response =
				client.send(request, HttpResponse.BodyHandlers.ofString());
			
			if(response.statusCode() == 200)
			{
				String body = response.body();
				int linkStart = body.indexOf("\"link\":\"") + 8;
				int linkEnd = body.indexOf("\"", linkStart);
				return body.substring(linkStart, linkEnd);
			}
			
			return null;
			
		}catch(IOException | InterruptedException e)
		{
			e.printStackTrace();
			return null;
		}
	}
}
