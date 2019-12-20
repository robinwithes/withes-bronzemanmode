package com.robinwithes.bronzemanmode;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BronzeManModePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BronzeManModePlugin.class);
		RuneLite.main(args);
	}
}