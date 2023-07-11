package atomicstryker.ruins.common;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed;
import net.minecraftforge.event.world.BlockEvent.BreakEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod(modid = RuinsMod.ModId, name = RuinsMod.ModName, version = RuinsMod.ModVersion, dependencies = "after:extrabiomes")
public class RuinsMod
{
    public static final String ModVersion = "${MOD_VERSION}";
    public static final String ModName = "${MOD_NAME}";
    public static final String ModId = "${MOD_ID}";

    public static final String TEMPLATE_PATH_MC_EXTRACTED = "config/ruins_config/";
    public static final String TEMPLATE_PATH_JAR = "ruins_config";

    public final static int DIR_NORTH = 0, DIR_EAST = 1, DIR_SOUTH = 2, DIR_WEST = 3;
    public static final String BIOME_ANY = "generic";

    private ConcurrentHashMap<Integer, WorldHandle> generatorMap;

    @NetworkCheckHandler
    public boolean checkModLists(Map<String, String> modList, Side side)
    {
        return true;
    }

    @EventHandler
    public void load(FMLInitializationEvent evt)
    {
        GameRegistry.registerWorldGenerator(new RuinsWorldGenerator(), 0);
        MinecraftForge.EVENT_BUS.register(this);

        ConfigFolderPreparator.copyFromJarIfNotPresent(this, new File(getMinecraftBaseDir(), TEMPLATE_PATH_MC_EXTRACTED));
    }

    @EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent evt)
    {
        generatorMap = new ConcurrentHashMap<>();
    }

    @EventHandler
    public void serverStarted(FMLServerStartingEvent evt)
    {
        evt.registerServerCommand(new CommandParseTemplate());
        evt.registerServerCommand(new CommandTestTemplate());
        evt.registerServerCommand(new CommandUndo());
    }

    private long nextInfoTime;

    @SubscribeEvent
    public void onBreakSpeed(BreakSpeed event)
    {
        WorldHandle wh = getWorldHandle(event.getEntity().getEntityWorld());
        if (wh != null && wh.fileHandle.enableStick)
        {
            ItemStack is = event.getEntityPlayer().getHeldItemMainhand();
            if (is != null && is.getItem() == Items.STICK && System.currentTimeMillis() > nextInfoTime)
            {
                nextInfoTime = System.currentTimeMillis() + 1000L;
                event.getEntityPlayer().sendMessage(new TextComponentString(String.format("BlockName [%s], blockID [%s], metadata [%d]", event.getState().getBlock().getUnlocalizedName(),
                        event.getState().getBlock().getRegistryName().getResourcePath(), event.getState().getBlock().getMetaFromState(event.getState()))));
            }
        }
    }

    @SubscribeEvent
    public void onBreak(BreakEvent event)
    {
        if (event.getPlayer() != null && !(event.getPlayer() instanceof FakePlayer))
        {
            WorldHandle wh = getWorldHandle(event.getWorld());
            if (wh != null && wh.fileHandle.enableStick)
            {
                ItemStack is = event.getPlayer().getHeldItemMainhand();
                if (is != null && is.getItem() == Items.STICK && System.currentTimeMillis() > nextInfoTime)
                {
                    nextInfoTime = System.currentTimeMillis() + 1000L;
                    event.getPlayer().sendMessage(new TextComponentString(String.format("BlockName [%s], blockID [%s], metadata [%d]", event.getState().getBlock().getUnlocalizedName(),
                            event.getState().getBlock().getRegistryName().getResourcePath(), event.getState().getBlock().getMetaFromState(event.getState()))));
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent
    public void eventWorldSave(WorldEvent.Save evt)
    {
        WorldHandle wh = getWorldHandle(evt.getWorld());
        if (wh != null)
        {
            wh.generator.flushPosFile(evt.getWorld().getWorldInfo().getWorldName());
        }
    }


    private class RuinsWorldGenerator implements IWorldGenerator
    {
        @Override
        public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
        {
            if (world.isRemote || !world.getWorldInfo().isMapFeaturesEnabled())
            {
                return;
            }

            final WorldHandle wh = getWorldHandle(world);
            if (wh != null)
            {
                int[] tuple = { chunkX, chunkZ };
                if (wh.currentlyGenerating.contains(tuple))
                {
                    System.err.printf("Ruins Mod caught recursive generator call at chunk [%d|%d]", chunkX, chunkZ);
                }
                else
                {
                    if (wh.fileHandle.allowsDimension(world.provider.getDimension()) && !wh.chunkLogger.catchChunkBug(chunkX, chunkZ))
                    {
                        wh.currentlyGenerating.add(tuple);
                        if (world.provider instanceof WorldProviderHell)
                        {
                            generateNether(world, random, tuple[0] * 16, tuple[1] * 16);
                        }
                        else
                        // normal world
                        {
                            generateSurface(world, random, tuple[0] * 16, tuple[1] * 16);
                        }
                        wh.currentlyGenerating.remove(tuple);
                    }
                }
            }
        }
    }

    private void generateNether(World world, Random random, int chunkX, int chunkZ)
    {
        WorldHandle wh = getWorldHandle(world);
        if (wh.fileHandle != null)
        {
            while (!wh.fileHandle.loaded)
            {
                Thread.yield();
            }
            wh.generator.generateNether(world, random, chunkX, chunkZ);
        }
    }

    private void generateSurface(World world, Random random, int chunkX, int chunkZ)
    {
        WorldHandle wh = getWorldHandle(world);
        if (wh.fileHandle != null)
        {
            while (!wh.fileHandle.loaded)
            {
                Thread.yield();
            }
            wh.generator.generateNormal(world, random, chunkX, chunkZ);
        }
    }

    private class WorldHandle
    {
        FileHandler fileHandle;
        RuinGenerator generator;
        ConcurrentLinkedQueue<int[]> currentlyGenerating;
        ChunkLoggerData chunkLogger;
    }

    private WorldHandle getWorldHandle(World world)
    {
        WorldHandle wh = null;
        if (!world.isRemote)
        {
            if (!generatorMap.containsKey(world.provider.getDimension()))
            {
                wh = new WorldHandle();
                initWorldHandle(wh, world);
                generatorMap.put(world.provider.getDimension(), wh);
            }
            else
            {
                wh = generatorMap.get(world.provider.getDimension());
            }
        }

        return wh;
    }

    private static File getWorldSaveDir(World world)
    {
        ISaveHandler worldsaver = world.getSaveHandler();

        if (worldsaver.getChunkLoader(world.provider) instanceof AnvilChunkLoader)
        {
            AnvilChunkLoader loader = (AnvilChunkLoader) worldsaver.getChunkLoader(world.provider);

            for (Field f : loader.getClass().getDeclaredFields())
            {
                if (f.getType().equals(File.class))
                {
                    try
                    {
                        f.setAccessible(true);
                        // System.out.println("Ruins mod determines World Save
                        // Dir to be at: "+saveLoc);
                        return (File) f.get(loader);
                    }
                    catch (Exception e)
                    {
                        System.out.println("Ruins mod failed trying to find World Save dir:");
                        e.printStackTrace();
                    }
                }
            }
        }

        return null;
    }

    public static File getMinecraftBaseDir()
    {
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT)
        {
            File file = FMLClientHandler.instance().getClient().mcDataDir;
            String abspath = file.getAbsolutePath();
            if (abspath.endsWith("."))
            {
                file = new File(abspath.substring(0, abspath.length() - 1));
            }
            return file;
        }
        return FMLCommonHandler.instance().getMinecraftServerInstance().getFile("");
    }

    private void initWorldHandle(WorldHandle worldHandle, World world)
    {
        // load in defaults
        try
        {
            File worlddir = getWorldSaveDir(world);
            worldHandle.fileHandle = new FileHandler(worlddir, world.provider.getDimension());
            worldHandle.generator = new RuinGenerator(worldHandle.fileHandle, world);
            worldHandle.currentlyGenerating = new ConcurrentLinkedQueue<>();

            worldHandle.chunkLogger = (ChunkLoggerData) world.getPerWorldStorage().getOrLoadData(ChunkLoggerData.class, "ruinschunklogger");
            if (worldHandle.chunkLogger == null)
            {
                worldHandle.chunkLogger = new ChunkLoggerData("ruinschunklogger");
                world.getPerWorldStorage().setData("ruinschunklogger", worldHandle.chunkLogger);
            }
        }
        catch (Exception e)
        {
            System.err.println("There was a problem loading the ruins mod:");
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
    }

}