package com.robinwithes.bronzemanmode;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.music.MusicPlugin;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUploadStyle;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    private ChatboxPanelManager chatboxPanelManager;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ChatCommandManager chatCommandManager;

    @Inject
    private DrawManager drawManager;

    @Inject
    private ImageCapture imageCapture;

    @Inject
    private ClientUI clientUi;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BronzeManModeOverlay bronzemanOverlay;

    @Inject
    private BronzeManModeConfig config;

    @Inject
    private ClientThread clientThread;

    @Getter
    private BufferedImage unlockImage = null;

    final int COLLECTION_LOG_GROUP_ID = 621;
    final int COLLECTION_VIEW = 35;
    final int COLLECTION_VIEW_SCROLLBAR = 36;
    final int COLLECTION_VIEW_HEADER = 19;
    private static final int GE_SEARCH_BUILD_SCRIPT = 751;
    private final String COUNT_COMMAND = "!count";
    private final String RESET_COMMAND = "!reset";
    private final String BACKUP_COMMAND = "!backup";
    private final String CONTINUE_COMMAND = "!continue";


    private ChatboxTextInput searchInput;
    private Widget searchButton;
    private Collection<Widget> itemEntries;
    private int iconOffset = -1;
    private volatile List<Integer> unlockedItems;
    private Color textColor = new Color(87, 87, 87, 0);
    private boolean readyToSave = true;
    private BufferedImage bronzeManHeadIcon;

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
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN) {
                setupChatName(getNameChatbox());
            }
        });

    }

    @Override
    protected void shutDown() throws Exception {
        super.shutDown();
        itemEntries = null;
        overlayManager.remove(bronzemanOverlay);
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN) {
                setupChatName(client.getLocalPlayer().getName());
            }
        });
    }

    /**
     * Loads players unlocks on login
     **/
    @Subscribe
    public void onGameStateChanged(GameStateChanged e) {
        if (e.getGameState() == GameState.LOGGED_IN) {
            loadPlayerUnlocks();
            setupImages();
        }
        if (e.getGameState() == GameState.LOGIN_SCREEN)
        {
            itemEntries = null;
        }
    }

    /**
     * Unlocks all new items that are currently not unlocked
     **/
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged e) {
        if (config.progressionPaused() && config.hardcoreBronzeMan()) {
            sendMessage("Your unlocks are still paused due to you dying as a hardcore bronzeman. Type !continue to unpause");
            return;
        }
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

            ItemComposition itemComposition = itemManager.getItemComposition(i.getId());
            int realItemId = itemComposition.getNote() == 799 ? itemComposition.getLinkedNoteId() : itemComposition.getId();
            ItemComposition realItemComposition = itemManager.getItemComposition(realItemId);

            if (!unlockedItems.contains(realItemId) && realItemComposition.isTradeable()) {
                queueItemUnlock(realItemId);
            }
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != COLLECTION_LOG_GROUP_ID) {
            return;
        }
        itemEntries = null;
        clientThread.invokeLater(() -> {
            Widget collectionViewHeader = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_HEADER);
            Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
            headerComponents[0].setText("Bronze Man Unlocks");
            headerComponents[1].setText("Unlocks: <col=ff0000>" + Integer.toString(unlockedItems.size()));
            if (headerComponents.length > 2) {
                headerComponents[2].setText("");
            }
            createSearchButton(collectionViewHeader);

            Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
            Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
            collectionView.deleteAllChildren();

            int index = 0;
            int scrollHeight = 1;
            int x = 0;
            int y = 0;
            int yIncrement = 40;
            int xIncrement = 42;
            for (Integer itemId : unlockedItems) {
                addItemToCollectionLog(collectionView, itemId, x, y, index);
                x = x + xIncrement;
                index++;
                if (x > 210) {
                    x = 0;
                    y = y + yIncrement;
                }
            }

            scrollHeight = ((unlockedItems.size() / 6) * yIncrement);
            collectionView.setScrollHeight(scrollHeight);
            collectionView.revalidateScroll();
            client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
            collectionView.setScrollY(0);
            scrollbar.setScrollY(0);
        });

    }

    private void createSearchButton(Widget header) {
        searchButton = header.createChild(-1, WidgetType.GRAPHIC);
        searchButton.setSpriteId(SpriteID.GE_SEARCH);
        searchButton.setOriginalWidth(18);
        searchButton.setOriginalHeight(17);
        searchButton.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
        searchButton.setOriginalX(5);
        searchButton.setOriginalY(20);
        searchButton.setHasListener(true);
        searchButton.setAction(1, "Open");
        searchButton.setOnOpListener((JavaScriptCallback) e -> openSearch());
        searchButton.setName("Search");
        searchButton.revalidate();
    }

    private void openSearch() {
        updateFilter("");
        client.playSoundEffect(SoundEffectID.UI_BOOP);
        searchButton.setAction(1, "Close");
        searchButton.setOnOpListener((JavaScriptCallback) e -> closeSearch());
        searchInput = chatboxPanelManager.openTextInput("Search unlock list")
                .onChanged(s -> clientThread.invokeLater(() -> updateFilter(s.trim())))
                .onClose(() ->
                {
                    clientThread.invokeLater(() -> updateFilter(""));
                    searchButton.setOnOpListener((JavaScriptCallback) e -> openSearch());
                    searchButton.setAction(1, "Open");
                })
                .build();
    }

    private void closeSearch()
    {
        updateFilter("");
        chatboxPanelManager.close();
        client.playSoundEffect(SoundEffectID.UI_BOOP);
    }

    private void updateFilter(String input)
    {
        final Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);

        if (collectionView == null)
        {
            return;
        }

        String filter = input.toLowerCase();
        updateList(collectionView, filter);
    }

    private void updateList(Widget collectionView, String filter)
    {

        if (itemEntries == null)
        {
            itemEntries = Arrays.stream(collectionView.getDynamicChildren())
                    .sorted(Comparator.comparing(Widget::getRelativeY))
                    .collect(Collectors.toList());
        }

        itemEntries.forEach(w -> w.setHidden(true));

        Collection<Widget> matchingItems = itemEntries.stream()
                .filter(w -> w.getName().toLowerCase().contains(filter))
                .collect(Collectors.toList());

        int x = 0;
        int y = 0;
        for (Widget entry : matchingItems)
        {
            entry.setHidden(false);
            entry.setOriginalY(y);
            entry.setOriginalX(x);
            entry.revalidate();
            x = x + 42;
            if (x > 210) {
                x = 0;
                y = y + 40;
            }
        }

        y += 3;

        int newHeight = 0;

        if (collectionView.getScrollHeight() > 0)
        {
            newHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
        }

        collectionView.setScrollHeight(y);
        collectionView.revalidateScroll();

        Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
        client.runScript(
                ScriptID.UPDATE_SCROLLBAR,
                scrollbar.getId(),
                collectionView.getId(),
                newHeight
        );
    }

    private void addItemToCollectionLog(Widget collectionView, Integer itemId, int x, int y, int index) {
        String itemName = itemManager.getItemComposition(itemId).getName();
        Widget newItem = collectionView.createChild(index, 5);
        newItem.setContentType(0);
        newItem.setItemId(itemId);
        newItem.setItemQuantity(1);
        newItem.setItemQuantityMode(0);
        newItem.setModelId(-1);
        newItem.setModelType(1);
        newItem.setSpriteId(-1);
        newItem.setBorderType(1);
        newItem.setFilled(false);
        newItem.setRelativeX(x);
        newItem.setRelativeY(y);
        newItem.setOriginalX(x);
        newItem.setOriginalY(y);
        newItem.setOriginalWidth(36);
        newItem.setOriginalHeight(32);
        newItem.setWidth(36);
        newItem.setHeight(32);
        newItem.setHasListener(true);
        newItem.setAction(1, "Inspect");
        newItem.setOnOpListener((JavaScriptCallback) e -> handleItemAction(itemName));
        newItem.setName(itemName);
        newItem.revalidate();
    }

    private void handleItemAction(String itemName) {

        if (itemName.equalsIgnoreCase("cat ears")) {
            sendMessage("Now we just need elf ears..");
        }

        final ChatMessageBuilder message = new ChatMessageBuilder()
                .append(ChatColorType.NORMAL)
                .append("It's a " + itemName);

        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.ITEM_EXAMINE)
                .runeLiteFormattedMessage(message.build())
                .build());
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == GE_SEARCH_BUILD_SCRIPT) {
            filterResults();
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (event.getEventName().equals("setChatboxInput")) {
            setupChatName(getNameChatbox());
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

        Widget[] children = client.getWidget(162, 50).getChildren();
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

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer().getName() == null ||
                chatMessage.getName() == null) {
            return;
        }

        String name = Text.removeTags(chatMessage.getName());
        name = Text.sanitize(name);
        String playerName = Text.removeTags(client.getLocalPlayer().getName());
        playerName = Text.sanitize(playerName);

        try {
            if (name.equals(playerName) || (name.substring(0, 3)
                    .equalsIgnoreCase("bmm"))) { //check if player has bmm in his name or if player is local player
                AddIconToMessage(chatMessage);
            }
        } catch (StringIndexOutOfBoundsException e) {
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath actorDeath) {
        if (!config.hardcoreBronzeMan() || client.isInInstancedRegion() ||
                actorDeath.getActor() != client.getLocalPlayer()) {
            return;
        }

        if (config.progressionPaused()) {
            sendMessage("You have perished and lost all your Bronze Man Mode unlocks. Well... You would have.. If your progression wasn't paused already..");
            return;
        }

        hardBackupUnlocks();
        hardResetUnlocks();
        config.progressionPaused(true);
        sendMessage("You have perished and lost all your Bronze Man Mode unlocks, getting new unlocks will be paused untill you type !continue.");
    }

    /**
     * Queues a new unlock to be properly displayed
     **/
    public void queueItemUnlock(int itemId) {
        unlockedItems.add(itemId);
        String itemName = itemManager.getItemComposition(itemId).getName();
        bronzemanOverlay.addItemUnlock(itemId);
        if (config.itemUnlockChatMessage()) {
            sendMessage("Item Unlocked: " + itemManager.getItemComposition(itemId).getName());
        }

        if (readyToSave) {
            readyToSave = false;
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    readyToSave = true;
                    savePlayerUnlocks();
                    if (config.screenshotUnlocks()) {
                        takeScreenshot("ItemUnlock " + itemName);
                    }
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
            if (client.getUsername() == "" || client.getUsername() == null) {
                return;
            }
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
            if (client.getUsername() == "" || client.getUsername() == null) {
                return;
            }
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
        final IndexedSprite[] modIcons = client.getModIcons();
        if (iconOffset != -1 || modIcons == null) {
            return;
        }
        unlockImage = ImageUtil.getResourceStreamFromClass(getClass(), "/unlock_image.png");
        bronzeManHeadIcon = ImageUtil.getResourceStreamFromClass(getClass(), "/bronzeman_icon.png");
        IndexedSprite indexedSprite = ImageUtil.getImageIndexedSprite(bronzeManHeadIcon, client);

        iconOffset = modIcons.length;

        final IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + 1);
        newModIcons[newModIcons.length - 1] = indexedSprite;

        client.setModIcons(newModIcons);
    }

    private void setupChatCommands() {
        chatCommandManager.registerCommand(COUNT_COMMAND, this::countItems);
        chatCommandManager.registerCommand(RESET_COMMAND, this::resetUnlocks);
        chatCommandManager.registerCommand(BACKUP_COMMAND, this::backupUnlocks);
        chatCommandManager.registerCommand(CONTINUE_COMMAND, this::continueBronzeManMode);
    }

    //continue's normal item unlocking mechanics
    private void continueBronzeManMode(ChatMessage chatMessage, String s) {
        if (!Text.sanitize(chatMessage.getMessageNode().getName())
                .equals(Text.sanitize(client.getLocalPlayer().getName()))) {
            return;
        }
        if (!config.progressionPaused()) {
            sendMessage("Unlock progression is not paused.");
            return;
        }
        config.progressionPaused(false);
        sendMessage("Unlock progression has been unpaused!");
    }

    private void backupUnlocks(ChatMessage chatMessage, String s) {
        if (!Text.sanitize(chatMessage.getMessageNode().getName())
                .equals(Text.sanitize(client.getLocalPlayer().getName()))) {
            return;
        }
        if (!config.backupCommand()) {
            return;
        }
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

        sendMessage("Successfully backed up unlock file to: " + RuneLite.PROFILES_DIR);
    }

    //same as backupunlocks but without safety checks
    private void hardBackupUnlocks() {
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
            Files.copy(originalPath, Paths.get(playerFolder.getPath() + "_" + sdf.format(cal.getTime()) + "hcdeath.backup"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        sendMessage("Saved a backup to " + RuneLite.PROFILES_DIR + " just in case..");
    }
    private void countItems(ChatMessage chatMessage, String s) {
        if (!config.countCommand()) {
            return;
        }
        MessageNode messageNode = chatMessage.getMessageNode();

        if (!Text.sanitize(messageNode.getName()).equals(Text.sanitize(client.getLocalPlayer().getName()))) {
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
        if (!Text.sanitize(chatMessage.getMessageNode().getName())
                .equals(Text.sanitize(client.getLocalPlayer().getName()))) {
            return;
        }
        if (!config.resetCommand()) {
            sendMessage("The reset command is not enabled in your settings.");
            return;
        }
        hardResetUnlocks();
        sendMessage("Unlocks succesfully reset!");
    }

    //The same as resetUnlocks() but without the safety check(s)
    private void hardResetUnlocks() {
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
    }

    private void sendMessage(String chatMessage) {
        final String message = new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append(chatMessage)
                .build();

        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(message)
                .build());
    }

    //screenshot stuff
    private void takeScreenshot(String fileName) {
        Consumer<Image> imageCallback = (img) ->
        {
            // This callback is on the game thread, move to executor thread
            executor.submit(() -> takeScreenshot(fileName, img));
        };

        drawManager.requestNextFrameListener(imageCallback);
    }

    private void takeScreenshot(String fileName, Image image) {
        BufferedImage screenshot =
                new BufferedImage(clientUi.getWidth(), clientUi.getHeight(), BufferedImage.TYPE_INT_ARGB);

        Graphics graphics = screenshot.getGraphics();
        int gameOffsetX = 0;
        int gameOffsetY = 0;

        // Draw the game onto the screenshot
        graphics.drawImage(image, gameOffsetX, gameOffsetY, null);
        imageCapture.takeScreenshot(screenshot, fileName, "Item Unlocks", false, ImageUploadStyle.NEITHER);
    }

    private void setupChatName(String name) {
        Widget chatboxInput = client.getWidget(WidgetInfo.CHATBOX_INPUT);
        if (chatboxInput != null) {
            String text = chatboxInput.getText();
            int idx = text.indexOf(':');
            if (idx != -1) {
                String newText = name + text.substring(idx);
                chatboxInput.setText(newText);
            }
        }
    }

    private String getNameChatbox() {
        Player player = client.getLocalPlayer();
        if (player != null && config.showBMMIcon()) {
            return setupBronzeManName(iconOffset, player.getName());
        }
        return player.getName();
    }

    private static String setupBronzeManName(int iconIndex, String name) {
        String nameAndIcon = "<img=" + iconIndex + ">" + name;
        return nameAndIcon;
    }


    private void AddIconToMessage(ChatMessage chatMessage) {
        if (!config.showBMMIcon()) return;
        String name = chatMessage.getName();
        if (!name.equals(Text.removeTags(name))) {
            return;
        }

        final MessageNode messageNode = chatMessage.getMessageNode();
        messageNode.setName(setupBronzeManName(iconOffset, name));
        chatMessageManager.update(messageNode);
        client.refreshChat();
    }

    //Block trading by consuming the event
    @Subscribe
    public void onMenuOptionClicked (MenuOptionClicked event)
    {
        if (!config.blockTrading()) {
            return;
        }

        String option = event.getMenuOption().toLowerCase();
        if (option.contains("trade with") || option.contains("accept trade"))
        {
            event.consume();
            sendMessage("You are a bronzeman, you stand alone... Sort of.");
        }
    }
}
