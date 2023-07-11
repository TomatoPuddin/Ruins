package atomicstryker.ruins.common.mixin;

import net.minecraft.tileentity.CommandBlockBaseLogic;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityCommandBlock;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TileEntityCommandBlock.class)
public class TileEntityCommandBlockMixin extends TileEntity implements ITickable {

    @Shadow
    @Final
    private CommandBlockBaseLogic commandBlockLogic;

    @Unique
    private static final String triggerCommandPrefix = "RUINSTRIGGER ";
    @Unique
    private static final int ruins$interval = 40;
    @Unique
    private int ruins$counter = 0;

    @Override
    public void update() {
        World world = getWorld();

        // check side
        if (world.isRemote)
            return;

        // cooldown
        if(ruins$counter++< ruins$interval)
            return;
        ruins$counter = 0;

        // check trigger
        if(!commandBlockLogic.getCommand().startsWith(triggerCommandPrefix))
            return;

        BlockPos pos = getPos();

        // check player
        if(world.getClosestPlayer(pos.getX(), pos.getY(), pos.getZ(), 64, true) == null)
            return;

        // trigger
        commandBlockLogic.setCommand(commandBlockLogic.getCommand().substring(triggerCommandPrefix.length()));
        commandBlockLogic.trigger(world);
        commandBlockLogic.setCommand("");

        // remove
        world.setBlockToAir(pos);
        System.out.printf("Ruins executed and killed Command Block at [%s]%n", pos);
    }
}
