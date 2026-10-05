package de.yourshika.betterpets.quickslots;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the item a pet is drawn with. On the server every Better Pets pet is a player head carrying a
 * skin texture, so the same head is rebuilt here from the texture value the plugin sends - the pet then
 * looks exactly like it does in the plugin's own chest menu.
 */
final class PetIcons {

    // Filled lazily, never in a static initialiser: an item can only be created once the game has loaded
    // its item data, which is the case in a world but not yet on the title screen.
    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    private PetIcons() {
    }

    /** The head for a base64 "textures" property value; a plain head if there is none. */
    static ItemStack head(final String texture) {
        return CACHE.computeIfAbsent(texture == null ? "" : texture, PetIcons::create);
    }

    private static ItemStack create(final String texture) {
        final ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        if (texture.isEmpty()) {
            return stack;
        }
        // A stable id per texture: the client caches skins by profile id, so two different skins must
        // never share one.
        final UUID id = UUID.nameUUIDFromBytes(("betterpets:" + texture).getBytes(StandardCharsets.UTF_8));
        final PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", texture)));
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(id, "better_pet", properties)));
        return stack;
    }
}
