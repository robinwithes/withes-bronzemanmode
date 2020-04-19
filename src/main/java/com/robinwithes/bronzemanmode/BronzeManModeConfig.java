package com.robinwithes.bronzemanmode;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bronzeman")
public interface BronzeManModeConfig extends Config {
    @ConfigItem(
            keyName = "resetCommand",
            name = "Enable reset command",
            description = "Enables the !reset command used for wiping your unlocked items."
    )
    default boolean resetCommand()
    {
        return false;
    }

    @ConfigItem(
            keyName = "countCommand",
            name = "Enable count command",
            description = "Enables the !count command used for counting your unlocked items."
    )
    default boolean countCommand()
    {
        return true;
    }

    @ConfigItem(
            keyName = "backupCommand",
            name = "Enable backup command",
            description = "Enables the !backup command used for backing up your unlocked items."
    )
    default boolean backupCommand()
    {
        return true;
    }

    @ConfigItem(
            keyName = "screenshotUnlocks",
            name = "Screenshot Unlocks",
            description = "Take a screenshot of item unlocks"
    )
    default boolean screenshotUnlocks()
    {
        return false;
    }

    @ConfigItem(
            keyName = "itemUnlockChatMessage",
            name = "Item Unlock Chat Notification",
            description = "Sends out a chat message when you unlocked a new item."
    )
    default boolean itemUnlockChatMessage()
    {
        return true;
    }

    @ConfigItem(
            keyName = "startItemsUnlocked",
            name = "",
            description = "",
            hidden = true
    )
    default boolean startItemsUnlocked()
    {
        return false;
    }

    @ConfigItem(
            keyName = "startItemsUnlocked",
            name = "",
            description = ""
    )
    void startItemsUnlocked(boolean condition);
}
