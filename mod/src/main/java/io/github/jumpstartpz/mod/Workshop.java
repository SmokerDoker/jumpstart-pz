package io.github.jumpstartpz.mod;

/**
 * Steam Workshop items in the game (subscribe, download, where they are); see {@link GameActions}.
 * Item states are Steam's EItemState flags.
 */
interface Workshop {
    long SUBSCRIBED = 1;
    long INSTALLED = 4;
    long NEEDS_UPDATE = 8;
    long DOWNLOADING = 16;
    long DOWNLOAD_PENDING = 32;

    /** Null when the game runs with Steam, otherwise why not. */
    String unavailableReason();

    /** Steam's state flags of an item, 0 when Steam does not know it. */
    long state(long itemId);

    /** Subscribes to the item and asks Steam to download it now. */
    boolean subscribeAndDownload(long itemId);

    /** The item's folder once it is installed, else null. */
    String installFolder(long itemId);
}
