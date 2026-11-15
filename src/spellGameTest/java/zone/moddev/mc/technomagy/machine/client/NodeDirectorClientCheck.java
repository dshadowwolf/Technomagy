package zone.moddev.mc.technomagy.machine.client;

import java.io.IOException;

import java.nio.file.Path;

import java.util.List;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction8;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import zone.moddev.mc.technomagy.Technomagy;
import zone.moddev.mc.technomagy.machine.MachineContent;
import zone.moddev.mc.technomagy.machine.NodeDirectorBlock;

/** Opt-in real-model rendering and world-space UV checks for the director preview (D21, D23). */
@EventBusSubscriber(modid = "technomagy", value = Dist.CLIENT)
public final class NodeDirectorClientCheck {
    private static boolean opened;
    private static boolean captured;
    private static int renderedFrames;
    // Lit-square centres in the approved 64-pixel artwork, in Bearing order.
    private static final float[][] LAMPS = {
        {31.5f, 13.5f}, {44.5f, 18.5f}, {49.5f, 31.5f}, {44.5f, 44.5f},
        {31.5f, 49.5f}, {18.5f, 44.5f}, {13.5f, 31.5f}, {18.5f, 18.5f}
    };

    private NodeDirectorClientCheck() {
    }

    /** Opens the preview only in a labelled disposable run after models have loaded. */
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (Boolean.getBoolean("technomagy.testDirector") && !opened
            && minecraft.screen instanceof TitleScreen) {
            opened = true;
            verifyModels();
            minecraft.setScreen(new Preview());
        }
    }

    /** Captures an actual rendered frame and closes the disposable client. */
    @SubscribeEvent
    public static void rendered(ScreenEvent.Render.Post event) {
        if (!Boolean.getBoolean("technomagy.testDirector") || captured
            || !(event.getScreen() instanceof Preview) || ++renderedFrames < 3) {
            return;
        }
        event.getGuiGraphics().flush();
        try (NativeImage image = Screenshot
            .takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(Path.of("node-director-directions.png"));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not capture node director preview", exception);
        }
        captured = true;
        Technomagy.LOGGER
            .info("DIRECTOR_CLIENT_CHECK: all 32 power/bearing states and isometric item verified");
        Minecraft.getInstance().stop();
    }

    /** Resolves every real model and proves the lamp lands in its named world-space direction. */
    private static void verifyModels() {
        var renderer = Minecraft.getInstance().getBlockRenderer();
        for (var state : MachineContent.NODE_DIRECTOR.get().getStateDefinition()
            .getPossibleStates()) {
            boolean hanging = state.getValue(NodeDirectorBlock.HANGING);
            var face = hanging ? Direction.DOWN : Direction.UP;
            var model = renderer.getBlockModel(state);
            List<BakedQuad> plates = model.getQuads(state, null, RandomSource.create(0)).stream()
                .filter(quad -> quad.getDirection() == face
                    && quad.getSprite().contents().name().getPath()
                        .startsWith("block/mana_redirector_"))
                .toList();
            if (plates.size() != 1) {
                throw new IllegalStateException("Expected one textured platform for " + state);
            }
            boolean powered = state.getValue(NodeDirectorBlock.POWERED);
            if (powered) {
                verifyLamp(state, plates.getFirst());
            } else if (!plates.getFirst().getSprite().contents().name().getPath()
                .equals("block/mana_redirector_top")) {
                throw new IllegalStateException("Unpowered platform has a blue lamp: " + state);
            }
            String side = "block/mana_redirector_side_" + (powered ? "on" : "off");
            long sideCount = model.getQuads(state, null, RandomSource.create(0)).stream()
                .filter(quad -> quad.getSprite().contents().name().getPath().equals(side)).count();
            if (sideCount != 4) {
                throw new IllegalStateException("Unexpected side-panel power appearance: " + state);
            }
        }
        var stack = new ItemStack(MachineContent.NODE_DIRECTOR_ITEM.get());
        var item = Minecraft.getInstance().getItemRenderer().getModel(stack, null, null, 0);
        var transform = item.getTransforms().getTransform(ItemDisplayContext.GUI);
        if (Math.abs(transform.rotation.x() - 30) > 0.001f
            || Math.abs(transform.rotation.y() - 225) > 0.001f
            || Math.abs(transform.scale.x() - 0.625f) > 0.001f) {
            throw new IllegalStateException("Inventory item is not using the isometric transform");
        }
        if (item.getQuads(null, null, RandomSource.create(0)).stream()
            .noneMatch(quad -> quad.getSprite().contents().name().getPath()
                .equals("block/mana_redirector_side_off"))) {
            throw new IllegalStateException("Inventory item is not using the unpowered artwork");
        }
    }

    /**
     * Solves the platform quad's affine UV map at the centre of the blue lamp. Inspecting baked
     * vertices catches ceiling flips that a correct-looking blockstate filename can hide.
     */
    private static void verifyLamp(BlockState state, BakedQuad quad) {
        var bearing = state.getValue(NodeDirectorBlock.DIRECTION);
        int textureIndex = state.getValue(NodeDirectorBlock.HANGING)
            ? Math.floorMod(4 - bearing.ordinal(), 8)
            : bearing.ordinal();
        String expected = "block/mana_redirector_"
            + NodeDirectorBlock.Bearing.values()[textureIndex].getSerializedName();
        var sprite = quad.getSprite();
        if (!sprite.contents().name().getPath().equals(expected)) {
            throw new IllegalStateException("Unexpected platform texture for " + state);
        }
        var lamp = LAMPS[textureIndex];
        // Add half a pixel to move from integer pixel-centre coordinates to texture edges.
        float u = sprite.getU((lamp[0] + 0.5f) / 64);
        float v = sprite.getV((lamp[1] + 0.5f) / 64);
        int[] data = quad.getVertices();
        int stride = data.length / 4;
        float du = value(data, stride, 4) - value(data, 0, 4);
        float dv = value(data, stride, 5) - value(data, 0, 5);
        float eu = value(data, 3 * stride, 4) - value(data, 0, 4);
        float ev = value(data, 3 * stride, 5) - value(data, 0, 5);
        float determinant = du * ev - dv * eu;
        float a = ((u - value(data, 0, 4)) * ev - (v - value(data, 0, 5)) * eu) / determinant;
        float b = (du * (v - value(data, 0, 5)) - dv * (u - value(data, 0, 4))) / determinant;
        float x = value(data, 0, 0) + a * (value(data, stride, 0) - value(data, 0, 0))
            + b * (value(data, 3 * stride, 0) - value(data, 0, 0)) - 0.5f;
        float z = value(data, 0, 2) + a * (value(data, stride, 2) - value(data, 0, 2))
            + b * (value(data, 3 * stride, 2) - value(data, 0, 2)) - 0.5f;
        var direction = Direction8.values()[bearing.ordinal()];
        if (!Float.isFinite(x) || !Float.isFinite(z) || sign(x) != direction.getStepX()
            || sign(z) != direction.getStepZ()) {
            throw new IllegalStateException(
                "Lamp points the wrong way for " + state + ": " + x + ", " + z);
        }
    }

    private static int sign(float coordinate) {
        return Math.abs(coordinate) < 0.01f ? 0 : (int) Math.signum(coordinate);
    }

    private static float value(int[] data, int offset, int field) {
        return Float.intBitsToFloat(data[offset + field]);
    }

    /** Two compass grids show the unchanged pillar from above and below. */
    private static final class Preview extends Screen {
        private static final int[] GRID = {7, 0, 1, 6, -1, 2, 5, 4, 3};

        private Preview() {
            super(Component.literal("Node Director: power and directions"));
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            gui.fill(0, 0, width, height, 0xff161c25);
            gui.drawCenteredString(font, title, width / 2, 12, 0xffe4d5b8);
            gui.drawCenteredString(font, "Powered floor (light 6)", width / 4, 34, 0xffabcde1);
            gui.drawCenteredString(font, "Powered ceiling (light 6)", 3 * width / 4, 34,
                0xffabcde1);
            int cell = Math.min(108, (width / 2 - 24) / 3);
            for (boolean hanging : new boolean[]{false, true}) {
                int center = hanging ? 3 * width / 4 : width / 4;
                int left = center - 3 * cell / 2;
                for (int index = 0; index < GRID.length; index++) {
                    int bearing = GRID[index];
                    if (bearing < 0) {
                        continue;
                    }
                    int x = left + index % 3 * cell;
                    int y = 52 + index / 3 * cell;
                    var state = MachineContent.NODE_DIRECTOR.get().defaultBlockState()
                        .setValue(NodeDirectorBlock.DIRECTION,
                            NodeDirectorBlock.Bearing.values()[bearing])
                        .setValue(NodeDirectorBlock.HANGING, hanging)
                        .setValue(NodeDirectorBlock.POWERED, true);
                    drawBlock(gui, state, x + cell / 2, y + cell / 2, cell * 0.52f);
                    gui.pose().pushPose();
                    gui.pose().translate(0, 0, 300);
                    gui.drawCenteredString(font, NodeDirectorBlock.Bearing.values()[bearing]
                        .getSerializedName().replace('_', '-'), x + cell / 2, y + cell - 10,
                        0xffdddddd);
                    gui.pose().popPose();
                }
            }
            int footer = height - 86;
            gui.drawCenteredString(font, "Unpowered floor (light 0)", width / 6, footer,
                0xffabcde1);
            gui.drawCenteredString(font, "Unpowered ceiling (light 0)", width / 2, footer,
                0xffabcde1);
            var off = MachineContent.NODE_DIRECTOR.get().defaultBlockState();
            drawBlock(gui, off, width / 6, footer + 49, 37);
            drawBlock(gui, off.setValue(NodeDirectorBlock.HANGING, true), width / 2, footer + 49,
                37);
            gui.drawCenteredString(font, "Inventory", 5 * width / 6, footer, 0xffabcde1);
            gui.pose().pushPose();
            gui.pose().translate(5 * width / 6 - 24, footer + 18, 300);
            gui.pose().scale(3, 3, 3);
            gui.renderItem(new ItemStack(MachineContent.NODE_DIRECTOR_ITEM.get()), 0, 0);
            gui.flush();
            gui.pose().popPose();
        }

        private void drawBlock(GuiGraphics gui, BlockState state, int x, int y, float scale) {
            gui.flush();
            Lighting.setupFor3DItems();
            var pose = gui.pose();
            pose.pushPose();
            pose.translate(x, y, 100);
            pose.scale(scale, -scale, scale);
            pose.mulPose(
                Axis.XP.rotationDegrees(state.getValue(NodeDirectorBlock.HANGING) ? -50 : 50));
            pose.mulPose(Axis.YP.rotationDegrees(20));
            pose.translate(-0.5f, -0.5f, -0.5f);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose,
                gui.bufferSource(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            gui.flush();
            pose.popPose();
            Lighting.setupForFlatItems();
        }
    }
}
