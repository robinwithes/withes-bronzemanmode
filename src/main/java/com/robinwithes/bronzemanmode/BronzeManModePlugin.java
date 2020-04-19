package com.robinwithes.bronzemanmode;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

@PluginDescriptor(
        name = "Bronze Man Mode",
        description = "",
        tags = {"bronzeman", "ironman", "pve"},
        enabledByDefault = false
)
@Slf4j
public class BronzeManModePlugin extends Plugin {

    @Inject
    private ItemManager itemManager;

    @Inject
    private Client client;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ChatCommandManager chatCommandManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BronzeManModeOverlay bronzemanOverlay;

    @Inject
    private BronzeManModeConfig config;

    @Getter
    private BufferedImage unlockImage = null;

    private static final int GE_SEARCH_BUILD_SCRIPT = 751;
    private final String COUNT_COMMAND = "!count";
    private final String RESET_COMMAND = "!reset";
    private final String BACKUP_COMMAND = "!backup";

    private volatile List<Integer> unlockedItems;
    private Color textColor = new Color(87, 87, 87, 0);
    private Widget grandExchangeWindow;
    private Widget grandExchangeChatBox;
    private boolean readyToSave = true;
    private BufferedImage chatHeadIcon;

    @Provides
    BronzeManModeConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(BronzeManModeConfig.class);
    }

    @Override
    protected void startUp() throws Exception {
        super.startUp();
        setupChatCommands();
        setupImages();
        unlockedItems = new ArrayList<>();
        loadPlayerUnlocks();
        overlayManager.add(bronzemanOverlay);
        unlockDefaultItems();
    }

    @Override
    protected void shutDown() throws Exception {
        super.shutDown();
        overlayManager.remove(bronzemanOverlay);
    }

    /**
     * Loads players unlocks on login
     **/
    @Subscribe
    public void onGameStateChanged(GameStateChanged e) {
        if (e.getGameState() == GameState.LOGGED_IN) {
            loadPlayerUnlocks();
        }
    }

    /**
     * Unlocks all new items that are currently not unlocked
     **/
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged e) {
        for (Item i : e.getItemContainer().getItems()) {
            if (i == null) {
                continue;
            }
            if (e.getContainerId() != 93 && e.getContainerId() != 95) {
                return; //if the inventory or bank is not updated then exit
            }
            if (i.getId() <= 1) {
                continue;
            }
            if (i.getQuantity() <= 0) {
                continue;
            }
            if (!unlockedItems.contains(i.getId())) {
                queueItemUnlock(i.getId());
            }
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() == GE_SEARCH_BUILD_SCRIPT)
        {
            filterResults();
        }
    }

    private void filterResults() {
        Widget grandExchangeWindow = client.getWidget(162, 53);
            if (grandExchangeWindow == null) {
                return;
            }
            if (client.getWidget(162, 53) == null) {
                return;
            }

            Widget[] children = client.getWidget(162, 53).getChildren();
            if (children == null) {
                return;
            }

            for (int i = 0; i < children.length; i += 3) {
                if (children[i] == null) {
                    continue;
                }
                if (i + 2 > children.length - 1 || children[i + 2] == null) {
                    continue;
                }

                if (!unlockedItems.contains(children[i + 2].getItemId())) {
                    children[i].setHidden(true);
                    Widget text = children[i + 1];
                    Widget image = children[i + 2];
                    text.setTextColor(textColor.getRGB());
                    text.setOpacity(210);
                    image.setOpacity(210);
                }
            }
    }

    /**
     * Queues a new unlock to be properly displayed
     **/
    public void queueItemUnlock(int itemId) {
        unlockedItems.add(itemId);
        bronzemanOverlay.addItemUnlock(itemId);
        sendMessage("Item Unlocked: " + itemManager.getItemComposition(itemId).getName());
        if (readyToSave) {
            readyToSave = false;
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    readyToSave = true;
                    savePlayerUnlocks();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    /**
     * Unlocks default items like a bond to a newly made profile
     **/
    private void unlockDefaultItems() {
        if (config.startItemsUnlocked()) {
            return;
        }

        config.startItemsUnlocked(true);
        queueItemUnlock(ItemID.COINS_995);
        queueItemUnlock(ItemID.OLD_SCHOOL_BOND);
        log.info("Unlocked starter items");
    }

    /**
     * Saves players unlocks to a .txt file every time they unlock a new item
     **/
    private void savePlayerUnlocks() {
        try {
            if (client.getUsername() == "" || client.getUsername() == null) return;
            File playerFolder = new File(RuneLite.PROFILES_DIR, client.getUsername());
            System.out.println("Saving unlocks to: " + playerFolder.getAbsolutePath());
            File playerFile = new File(playerFolder, "bronzeman-unlocks.txt");
            PrintWriter w = new PrintWriter(playerFile);
            for (int itemId : unlockedItems) {
                w.println(itemId);
            }
            w.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads a players' unlocks every time they login
     **/
    private void loadPlayerUnlocks() {
        unlockedItems.clear();
        try {
            if (client.getUsername() == "" || client.getUsername() == null) return;
            File playerFolder = new File(RuneLite.PROFILES_DIR, client.getUsername());
            if (!playerFolder.exists()) {
                playerFolder.mkdirs();
            }
            File playerFile = new File(playerFolder, "bronzeman-unlocks.txt");
            if (!playerFile.exists()) {
                playerFile.createNewFile();
            }
            else {
                BufferedReader r = new BufferedReader(new FileReader(playerFile));
                String l;
                while ((l = r.readLine()) != null) {
                    unlockedItems.add(Integer.parseInt(l));
                }
                r.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads image files
     **/
    private void setupImages() {
        unlockImage = ImageUtil.getResourceStreamFromClass(getClass(), "/unlock_image.png");
        BufferedImage image = ImageUtil.getResourceStreamFromClass(getClass(), "/bronzeman_icon.png");
    }

    private void setupChatCommands() {
        chatCommandManager.registerCommand(COUNT_COMMAND, this::countItems);
        chatCommandManager.registerCommand(RESET_COMMAND, this::resetUnlocks);
        chatCommandManager.registerCommand(BACKUP_COMMAND, this::backupUnlocks);
    }

    private void backupUnlocks(ChatMessage chatMessage, String s) {
        File playerFolder = new File(RuneLite.PROFILES_DIR, client.getUsername());
        if (!playerFolder.exists()) {
            return;
        }
        File playerFile = new File(playerFolder, "bronzeman-unlocks.txt");
        if (!playerFile.exists()) {
            return;
        }

        Path originalPath = playerFile.toPath();
        try {
            Calendar cal = Calendar.getInstance();
            SimpleDateFormat sdf = new SimpleDateFormat("MM_WW_HH_mm_ss");
            Files.copy(originalPath, Paths.get(playerFolder.getPath() + "_" + sdf.format(cal.getTime()) + ".backup"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        sendMessage("Successfully backed up file!");
    }

    private void countItems(ChatMessage chatMessage, String s) {
        MessageNode messageNode = chatMessage.getMessageNode();

        if (!Text.sanitize(messageNode.getName()).equals(Text.sanitize(client.getLocalPlayer().getName())))
        {
            return;
        }

        final ChatMessageBuilder builder = new ChatMessageBuilder()
                .append(ChatColorType.NORMAL)
                .append("I have unlocked " + Integer.toString(unlockedItems.size()) + " items.")
                .append(ChatColorType.HIGHLIGHT);

        String response = builder.build();

        messageNode.setRuneLiteFormatMessage(response);
        chatMessageManager.update(messageNode);
        client.refreshChat();
    }

    private void resetUnlocks(ChatMessage chatMessage, String s) {
        config.startItemsUnlocked(false);
        try {
            File playerFolder = new File(RuneLite.PROFILES_DIR, client.getUsername());
            File playerFile = new File(playerFolder, "bronzeman-unlocks.txt");
            playerFile.delete();
            unlockedItems.clear();
            savePlayerUnlocks();
            unlockDefaultItems();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        sendMessage("Unlocks succesfully reset!");

    }

    private void sendMessage(String chatMessage)
    {
        final String message = new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append(chatMessage)
                .build();

        chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(message)
                        .build());
    }
}
