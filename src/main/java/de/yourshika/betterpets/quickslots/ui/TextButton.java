package de.yourshika.betterpets.quickslots.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A button in the mod's own look: wood with a copper rim that brightens under the mouse, optionally with
 * an icon in front of the label. It is still a regular game button underneath, so keyboard navigation,
 * Enter/Space and narration all work as usual.
 */
public class TextButton extends AbstractButton {

    private final Identifier icon;
    private final Runnable action;
    private final Anim.FrameTimer timer = new Anim.FrameTimer();
    private float glow;

    public TextButton(final int x, final int y, final int width, final int height, final Component label, final Identifier icon, final Runnable action) {
        super(x, y, width, height, label);
        this.icon = icon;
        this.action = action;
    }

    @Override
    public void onPress(final InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        glow = Anim.approach(glow, active && isHoveredOrFocused() ? 1.0F : 0.0F, 16.0F, timer.tick());
        Sprites.draw(graphics, active ? Sprites.BUTTON : Sprites.BUTTON_DISABLED, getX(), getY(), width, height);
        // The lit version is laid over the plain one, so the change is a fade rather than a flip.
        Sprites.draw(graphics, Sprites.BUTTON_HOVER, getX(), getY(), width, height, Palette.fade(Palette.WHITE, glow));

        final Font font = Minecraft.getInstance().font;
        final int iconWidth = icon == null ? 0 : 12;
        final boolean labelled = !getMessage().getString().isEmpty();
        final int gap = icon != null && labelled ? 3 : 0;
        final int labelWidth = Math.min(font.width(getMessage()), width - 8 - iconWidth - gap);
        int x = getX() + (width - iconWidth - gap - labelWidth) / 2;
        final int centreY = getY() + height / 2;
        if (icon != null) {
            // The artwork icons are 16 pixels; three quarters of that sits well inside a button.
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, centreY - 6.0F);
            graphics.pose().scale(0.75F, 0.75F);
            Sprites.draw(graphics, icon, 0, 0, 16, 16, active ? Palette.WHITE : Palette.fade(Palette.WHITE, 0.4F));
            graphics.pose().popMatrix();
            x += iconWidth + gap;
        }
        if (labelled) {
            final int colour = active ? Palette.mix(Palette.TEXT_DIM, Palette.TEXT, glow) : Palette.TEXT_FAINT;
            graphics.text(font, Draw.clip(font, getMessage(), labelWidth), x, centreY - 4, colour);
        }
    }

    @Override
    public void playDownSound(final SoundManager soundManager) {
        UiSounds.click();
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
