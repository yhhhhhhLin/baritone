package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 挖掘指定物品，如果满了自动到达指定箱子丢弃
 */
public class MineAndDropCommand extends Command {

    private static final Logger log = LoggerFactory.getLogger(MineAndDropCommand.class);

    public MineAndDropCommand(IBaritone baritone) {
        super(baritone, "mineAndDrop");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        // 获取挖掘数量（默认为 0 即不限量）
        int quantity = args.getAsOrDefault(Integer.class, 0);

        // 获取目标容器位置
        BlockOptionalMeta destination = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);

        // 获取要挖掘的物品
        List<BlockOptionalMeta> targetBlocks = new ArrayList<>();
        while (args.hasAny()) {
            targetBlocks.add(args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE));
        }

        log.info("开始挖掘，目标物品: {}", targetBlocks);
        baritone.getMineProcess().mine(quantity, targetBlocks.toArray(new BlockOptionalMeta[0]));

        // 异步监控背包状态
        CompletableFuture.runAsync(() -> monitorInventoryAndHandle(destination, quantity, targetBlocks));
    }

    private void monitorInventoryAndHandle(BlockOptionalMeta destination, int quantity, List<BlockOptionalMeta> targetBlocks) {
        while (baritone.getMineProcess().isActive()) {
            if (isInventoryFull()) {
                log.info("背包已满，取消挖掘并前往目标箱子");
                baritone.getMineProcess().cancel();

                // 异步执行丢弃逻辑
                goToDestination(destination).thenRun(() -> {
                    dropItemsAtChest(destination, targetBlocks.getFirst());
                    log.info("物品已丢弃，返回继续挖掘");
                    baritone.getMineProcess().mine(quantity, targetBlocks.toArray(new BlockOptionalMeta[0]));
                });
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("监控线程中断: {}", e.getMessage());
                break;
            }
        }
    }

    private boolean isInventoryFull() {
        Inventory inventory = ctx.player().getInventory();
        return inventory.getFreeSlot() == -1; // 背包满返回 true
    }

    private CompletableFuture<Void> goToDestination(BlockOptionalMeta destination) {
        return CompletableFuture.runAsync(() -> {
            log.info("前往目标容器位置: {}", destination);

            baritone.getGetToBlockProcess().getToBlock(destination);
            while (baritone.getGetToBlockProcess().isActive()) {
                try {
                    Thread.sleep(100); // 简单等待，避免阻塞主线程
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("前往目标位置时被中断: {}", e.getMessage());
                    return;
                }
            }

            log.info("已到达目标容器位置");
        });
    }

    private void dropItemsAtChest(BlockOptionalMeta destination, BlockOptionalMeta targetBlock) {
        log.info("开始将物品丢入容器: {}", destination);
        // 获取玩家实例
        LocalPlayer player = ctx.player();
        if (player == null) {
            log.error("无法获取玩家实例");
            return;
        }

        // 遍历背包，将目标物品移动到容器
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack itemStack = player.getInventory().getItem(slot);
            if (!itemStack.isEmpty() && matchesTargetBlock(itemStack, targetBlock)) {
                // 模拟点击，将物品从背包槽位移动到容器
                ctx.playerController().windowClick(player.containerMenu.containerId, slot, 0, ClickType.QUICK_MOVE, player);
                log.info("将物品 {} 从背包槽位 {} 丢入容器", itemStack, slot);
            }
        }

        log.info("背包清理完成！");
    }

    private boolean matchesTargetBlock(ItemStack stack, BlockOptionalMeta targetBlock) {
        if (stack == null || stack.isEmpty() || targetBlock == null) {
            return false;
        }

        String itemId = stack.getItem().getDescriptionId();

        // 获取目标物品块的注册名称
        String targetBlockId = targetBlock.getBlock().getDescriptionId();

        // 检查是否匹配
        boolean matches = itemId.equals(targetBlockId);
        log.debug("匹配检查: 背包物品 [{}], 目标物品 [{}], 匹配结果: {}", itemId, targetBlockId, matches);

        return matches;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        args.getAsOrDefault(Integer.class, 0);
        while (args.has(2)) {
            args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
        }
        return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
    }

    @Override
    public String getShortDesc() {
        return "Mine blocks and drop items into a specified chest";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The mineAndDrop command tells Baritone to mine specified blocks.",
                "If the inventory is full, it will go to the specified chest coordinates and drop items.",
                "",
                "Usage:",
                "> mineAndDrop <quantity> <x> <y> <z> <block>...",
                "Example:",
                "> mineAndDrop 64 100 65 200 diamond_ore - Mines up to 64 diamonds and drops them at (100, 65, 200)."
        );
    }
}
