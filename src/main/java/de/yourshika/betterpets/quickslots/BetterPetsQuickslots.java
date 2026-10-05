package de.yourshika.betterpets.quickslots;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client companion for servers running the Better Pets plugin. The plugin does all the work (it owns the
 * pets, the slots and every rule); this mod only adds what a server cannot give a vanilla client: real
 * key bindings, a proper screen to fill the slots, and a button in the plugin's chest menu.
 *
 * <p>On a server without the plugin the mod stays completely passive.</p>
 */
public final class BetterPetsQuickslots implements ClientModInitializer {

    public static final String MOD_ID = "betterpets-quickslots";
    static final Logger LOGGER = LoggerFactory.getLogger("BetterPetsQuickslots");

    @Override
    public void onInitializeClient() {
        ModConfig.load();
        PayloadTypeRegistry.serverboundPlay().register(QuickslotPayload.TYPE, QuickslotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QuickslotPayload.TYPE, QuickslotPayload.CODEC);
        Keybinds.register();
        QuickslotClient.register();
        QuickslotHud.register();
        MenuButton.register();
        DevPreview.registerIfRequested();
    }
}
