package gjum.minecraft.liteloader.beancount;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.mumfrey.liteloader.PacketHandler;
import com.mumfrey.liteloader.PostRenderListener;
import com.mumfrey.liteloader.Tickable;
import com.mumfrey.liteloader.core.LiteLoader;
import com.mumfrey.liteloader.modconfig.ConfigStrategy;
import com.mumfrey.liteloader.modconfig.ExposableOptions;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.VertexBuffer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock;
import net.minecraft.network.play.server.SPacketOpenWindow;
import net.minecraft.network.play.server.SPacketWindowItems;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;

@ExposableOptions(strategy = ConfigStrategy.Versioned, filename = "beancount.json")
public class LiteModBeanCount implements PacketHandler, PostRenderListener, Tickable {
    private static KeyBinding toggleVisibleKeyBind = new KeyBinding("key.beancount.toggle", Keyboard.KEY_F12, "key.categories.beancount");
    private static KeyBinding resetKeyBind = new KeyBinding("key.beancount.reset", Keyboard.KEY_F12, "key.categories.beancount");

    @Expose
    @SerializedName("csvPath")
    String csvPath = "beanCount.csv"; // TODO in mod data directory

    @Expose
    @SerializedName("enabled")
    boolean enabled = true;

    @Expose
    @SerializedName("color_r")
    int color_r = 255;

    @Expose
    @SerializedName("color_g")
    int color_g = 255;

    @Expose
    @SerializedName("color_b")
    int color_b = 255;

    private final HashSet<BlockPos> openedPositions = new HashSet<BlockPos>();
    private final LinkedList<AxisAlignedBB> chestCuboids = new LinkedList<AxisAlignedBB>();
    private final LinkedList<ItemStack> chestedItems = new LinkedList<ItemStack>();
    private BlockPos chestPos = null;
    private int slotCount = 0;

    @Override
    public String getName() {
        return "Count chest contents";
    }

    @Override
    public String getVersion() {
        return "0.0.1";
    }

    @Override
    public void init(File configPath) {
        LiteLoader.getInput().registerKeyBinding(toggleVisibleKeyBind);
        LiteLoader.getInput().registerKeyBinding(resetKeyBind);
    }

    @Override
    public void upgradeSettings(String version, File configPath, File oldConfigPath) {
    }

    @Override
    public List<Class<? extends Packet<?>>> getHandledPackets() {
        ArrayList<Class<? extends Packet<?>>> classes = new ArrayList<Class<? extends Packet<?>>>();
        classes.add(SPacketOpenWindow.class);
        classes.add(SPacketWindowItems.class);
        classes.add(CPacketPlayerTryUseItemOnBlock.class);
        return classes;
    }

    @Override
    public boolean handlePacket(INetHandler netHandler, Packet<?> packet) {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (packet instanceof CPacketPlayerTryUseItemOnBlock) {
                CPacketPlayerTryUseItemOnBlock p = (CPacketPlayerTryUseItemOnBlock) packet;
                Block clickedBlock = minecraft.theWorld.getBlockState(p.getPos()).getBlock();
                if (clickedBlock == Blocks.CHEST || clickedBlock == Blocks.TRAPPED_CHEST) {
                    chestPos = p.getPos();
                }
            }
            if (packet instanceof SPacketOpenWindow) {
                SPacketOpenWindow p = (SPacketOpenWindow) packet;
                if ("minecraft:chest".equals(p.getGuiId())) {
                    // XXX ??? record id???
                    slotCount = p.getSlotCount();
                }
            }
            if (packet instanceof SPacketWindowItems) {
                recordChest(((SPacketWindowItems) packet).getItemStacks());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true; // packet will be handled normally
    }

    private void recordChest(ItemStack[] itemStacks) {
        // TODO track num of stacks per item type to calculate current vs. max compactness
        if (chestPos == null) return;
        if (openedPositions.contains(chestPos)) {
            return; // already opened
        }
        openedPositions.add(chestPos);

        // prevent connected chest from counting twice and show as single cuboid
        BlockPos minPos = chestPos;
        int sizeX = 1;
        int sizeZ = 1;
        if (isSameBlock(chestPos, chestPos.add(-1, 0, 0))) {
            minPos = minPos.add(-1, 0, 0);
            openedPositions.add(minPos);
            sizeX += 1;
        }
        if (isSameBlock(chestPos, chestPos.add(0, 0, -1))) {
            minPos = minPos.add(0, 0, -1);
            openedPositions.add(minPos);
            sizeZ += 1;
        }
        if (isSameBlock(chestPos, chestPos.add(1, 0, 0))) {
            openedPositions.add(chestPos.add(1, 0, 0));
            sizeX += 1;
        }
        if (isSameBlock(chestPos, chestPos.add(0, 0, 1))) {
            openedPositions.add(chestPos.add(0, 0, 1));
            sizeZ += 1;
        }

        int nonemptySlots = 0;
        int numItems = 0;
        for (int i = 0; i < slotCount; i++) {
            ItemStack itemStack = itemStacks[i];
            if (itemStack != null && itemStack.stackSize > 0) {
                chestedItems.add(itemStack);
                nonemptySlots += 1;
                numItems += itemStack.stackSize;
            }
        }

        // highlight as opened
        chestCuboids.add(new AxisAlignedBB(minPos, minPos.add(sizeX, 1, sizeZ)));

        showChatMsg(String.format("Added %s slots with %s items from chest at %s", nonemptySlots, numItems, chestPos));
    }

    private boolean isSameBlock(BlockPos posA, BlockPos posB) {
        WorldClient theWorld = Minecraft.getMinecraft().theWorld;
        return theWorld.getBlockState(posA).getBlock() == theWorld.getBlockState(posB).getBlock();
    }

    @Override
    public void onTick(Minecraft minecraft, float partialTicks, boolean inGame, boolean clock) {
        if (inGame && Minecraft.isGuiEnabled()) {
            try {
                if (toggleVisibleKeyBind.isPressed()) {
                    enabled = !enabled;
                    LiteLoader.getInstance().writeConfig(this);
                    showChatMsg("overlay " + (enabled ? "shown" : "hidden"));
                }
                if (resetKeyBind.isPressed()) {
                    if (!chestedItems.isEmpty()) {
                        HashMap<String, Integer> itemCounts = countItems();
                        writeCsv(itemCounts);
                        int totalItems = countItems(itemCounts);
                        showChatMsg(String.format("Saved and reset counts of %s items in %s chests, wrote to %s ", totalItems, chestCuboids.size(), csvPath));
                    }

                    openedPositions.clear();
                    chestCuboids.clear();
                    chestedItems.clear();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private HashMap<String, Integer> countItems() {
        HashMap<String, Integer> itemCounts = new HashMap<String, Integer>();
        for (ItemStack itemStack : chestedItems) {
            String key = itemStack.getDisplayName();
            Integer prevCount = itemCounts.get(key);
            if (prevCount == null) prevCount = 0;
            itemCounts.put(key, itemStack.stackSize + prevCount);
        }
        return itemCounts;
    }

    private int countItems(HashMap<String, Integer> itemCounts) {
        int totalItems = 0;
        for (String itemName : itemCounts.keySet()) {
            totalItems += itemCounts.get(itemName);
        }
        return totalItems;
    }

    private void writeCsv(HashMap<String, Integer> itemCounts) throws FileNotFoundException {
        PrintWriter writer = new PrintWriter(csvPath);
        writer.println("itemname,totalcount");
        for (String itemName : itemCounts.keySet()) {
            Integer count = itemCounts.get(itemName);
            writer.println(itemName + "," + count);
        }
        writer.close();
    }

    private void showChatMsg(String msg) {
        if (!enabled) return;
        Minecraft.getMinecraft().thePlayer.addChatComponentMessage(new TextComponentString("[BeanCount] " + msg));
    }

    @Override
    public void onPostRenderEntities(float partialTicks) {
    }

    @Override
    public void onPostRender(float partialTicks) {
        if (enabled) {
            EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
            if (p == null) return;
            double x = p.lastTickPosX + (p.posX - p.lastTickPosX) * partialTicks;
            double y = p.lastTickPosY + (p.posY - p.lastTickPosY) * partialTicks;
            double z = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partialTicks;
            glPushMatrix();
            glTranslated(-x, -y, -z);
            setupOverlayDrawing();

            glColor4d(color_r / 255.0, color_g / 255.0, color_b / 255.0, 0.8);
            for (AxisAlignedBB bb : chestCuboids) {
                drawCrossedOutlinedBoundingBox(bb);
            }

            glColor4d(color_r / 255.0, color_g / 255.0, color_b / 255.0, 0.2);
            for (AxisAlignedBB bb : chestCuboids) {
                drawBoundingBoxQuads(bb);
            }

            teardownOverlayDrawing();
            glPopMatrix();
        }
    }

    /**
     * Translate the center to the player.
     * glPushMatrix and glPopMatrix have to be called before and after accordingly.
     */
    public static boolean setupMatrixForPlayer(float partialTicks) {
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        if (p == null) return false;
        double x = p.lastTickPosX + (p.posX - p.lastTickPosX) * partialTicks;
        double y = p.lastTickPosY + (p.posY - p.lastTickPosY) * partialTicks;
        double z = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partialTicks;
        glTranslated(-x, -y, -z);
        return true;
    }

    public static void setupOverlayDrawing() {
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(770, 771);
    }

    public static void teardownOverlayDrawing() {
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_TEXTURE_2D);
    }

    public static void drawBoundingBoxQuads(AxisAlignedBB bb) {
        VertexFormat format = DefaultVertexFormats.POSITION;
        Tessellator tess = Tessellator.getInstance();
        VertexBuffer buffer = tess.getBuffer();

        buffer.begin(GL_QUADS, format);
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        tess.draw();

        buffer.begin(GL_QUADS, format);
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        tess.draw();

        buffer.begin(GL_QUADS, format);
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        tess.draw();

        buffer.begin(GL_QUADS, format);
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        tess.draw();

        buffer.begin(GL_QUADS, format);
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        tess.draw();

        buffer.begin(GL_QUADS, format);
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        tess.draw();
    }

    public static void drawCrossedOutlinedBoundingBox(AxisAlignedBB bb) {
        VertexFormat format = DefaultVertexFormats.POSITION;
        Tessellator tess = Tessellator.getInstance();
        VertexBuffer buffer = tess.getBuffer();

        buffer.begin(GL_LINE_STRIP, format);
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        tess.draw();

        buffer.begin(GL_LINE_STRIP, format);
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        tess.draw();

        buffer.begin(GL_LINE_STRIP, format);
        buffer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        tess.draw();

        buffer.begin(GL_LINE_STRIP, format);
        buffer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        tess.draw();

        buffer.begin(GL_LINE_STRIP, format);
        buffer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        tess.draw();

        buffer.begin(GL_LINE_STRIP, format);
        buffer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        buffer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        tess.draw();
    }

}
