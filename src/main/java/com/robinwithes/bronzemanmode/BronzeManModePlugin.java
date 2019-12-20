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
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

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
    private Client client;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BronzeManModeOverlay bronzemanOverlay;

    @Inject
    private BronzeManModeConfig config;

    @Getter
    private BufferedImage unlockImage = null;

    private volatile List<Integer> unlockedItems;
    private Color textColor = new Color(87, 87, 87, 0);
    private Widget grandExchangeWindow;
    private Widget grandExchangeChatBox;
    private boolean readyToSave = true;
    private volatile boolean geThreadRunning = false;

    @Provides
    BronzeManModeConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(BronzeManModeConfig.class);
    }

    @Override
    protected void startUp() throws Exception {
        super.startUp();
        loadUnlockImage();
        unlockedItems = new ArrayList<>();
        loadPlayerUnlocks();
        overlayManager.add(bronzemanOverlay);
        unlockDefaultItems();
    }

    @Override
    protected void shutDown() throws Exception {
        super.shutDown();
        overlayManager.remove(bronzemanOverlay);
        geThreadRunning = false;
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

    /**
     * Loads GrandExchange widgets for further manipulation of the interface
     **/
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e) {
        if (!geThreadRunning) {
            geThreadRunning = true;
            System.out.println("Starting GE Thread now.");
            new Thread(() -> handleGESearchWindow()).start();
        }

        switch (e.getGroupId()) {
            case WidgetID.GRAND_EXCHANGE_GROUP_ID:
                grandExchangeWindow = client.getWidget(WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER);
                break;
            case WidgetID.CHATBOX_GROUP_ID:
                grandExchangeWindow = null;
                grandExchangeChatBox = client.getWidget(WidgetInfo.CHATBOX);
                break;
        }
    }

    /**
     * Setup the thread that handles greying out stuff on the GE search window
     */
    private void handleGESearchWindow() {
        while (geThreadRunning) {
            if (grandExchangeWindow == null || grandExchangeChatBox == null) {
                continue;
            }
            if (client.getWidget(162, 53) == null) {
                continue;
            }

            Widget[] children = client.getWidget(162, 53).getChildren();
            if (children == null) {
                continue;
            }

            for (int i = 0; i < children.length; i += 3) {
                if (children[i] == null) {
                    continue;
                }
                if (i + 2 > children.length - 1 || children[i + 2] == null) {
                    continue;
                }

                System.out.println(children[i + 2].getItemId());
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
    }

    /**
     * Queues a new unlock to be properly displayed
     **/
    public void queueItemUnlock(int itemId) {
        unlockedItems.add(itemId);
        bronzemanOverlay.addItemUnlock(itemId);
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
            File playerFolder = new File(RuneLite.PROFILES_DIR, client.getUsername());
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
     * Loads a players unlcoks everytime they login
     **/
    private void loadPlayerUnlocks() {
        unlockedItems.clear();
        try {
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
     * Downloads the item-unlock png file to display unlocks
     **/
    private void loadUnlockImage() {
        try {
            File imageFile = new File(RuneLite.RUNELITE_DIR, "item-unlocked.png");
            if (!imageFile.exists()) {
                InputStream in = new URL("https://i.imgur.com/KWVNlsq.png").openStream();
                Files.copy(in, Paths.get(imageFile.getPath()));
            }
            unlockImage = ImageIO.read(imageFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        String messageSender = chatMessage.getName();
        String playerName = client.getLocalPlayer().getName();
        if (playerName == null) {
            return; //client not loaded in yet
        }
        messageSender = messageSender.replace(" ", ""); //The space in the first arg is not a space but a special character
        playerName = playerName.replace(" ", "");
        if (!messageSender.equals(playerName)) {
            return;
        }
        if (config.resetCommand() && chatMessage.getMessage().toLowerCase().equals("!reset")) {
            resetUnlocks();
        }
        else if (config.countCommand() && chatMessage.getMessage().toLowerCase().equals("!count")) {
            sendMessage("Unlocked item count: " + unlockedItems.size());
        }
        else if (config.countCommand() && chatMessage.getMessage().toLowerCase().equals("!backup")) {
            backupUnlocks();
        }
    }

    private void backupUnlocks() {
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

    private void resetUnlocks() {
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

    private void sendMessage(String text) {
        final ChatMessageBuilder message = new ChatMessageBuilder()
                .append(ChatColorType.NORMAL)
                .append(text)
                .append(ChatColorType.HIGHLIGHT);

        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.GAMEMESSAGE)
                .runeLiteFormattedMessage(message.build())
                .build());
    }

}
