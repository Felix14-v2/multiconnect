package net.earthcomputer.multiconnect.protocols.generic.blockconnections;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction8;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

public class ChunkConnector {
    private final LevelChunk chunk;
    private final BlockConnectionsLevelView worldView;
    private final BlockConnector connector;
    private final EnumMap<Direction8, IntSet> blocksNeedingUpdate;

    public ChunkConnector(LevelChunk chunk, BlockConnector connector, EnumMap<Direction8, IntSet> blocksNeedingUpdate) {
        this.chunk = chunk;
        this.worldView = new BlockConnectionsLevelView(chunk.getLevel());
        this.connector = connector;
        this.blocksNeedingUpdate = blocksNeedingUpdate;
    }

    public static Direction8 directionForPos(BlockPos pos) {
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        if (x == 0) {
            if (z == 0) {
                return Direction8.NORTH_WEST;
            } else if (z == 15) {
                return Direction8.SOUTH_WEST;
            } else {
                return Direction8.WEST;
            }
        } else if (x == 15) {
            if (z == 0) {
                return Direction8.NORTH_EAST;
            } else if (z == 15) {
                return Direction8.SOUTH_EAST;
            } else {
                return Direction8.EAST;
            }
        } else {
            if (z == 0) {
                return Direction8.NORTH;
            } else if (z == 15) {
                return Direction8.SOUTH;
            } else {
                return null;
            }
        }
    }

    public static int packLocalPos(int minY, BlockPos pos) {
        return (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | (((pos.getY() - minY) & 2047) << 8);
    }

    private int packLocalPos(BlockPos pos) {
        return packLocalPos(worldView.getMinY(), pos);
    }

    private int unpackLocalX(int packed) {
        return packed & 15;
    }

    private int unpackLocalY(int packed) {
        return (packed >> 8) & 2047 + worldView.getMinY();
    }

    private int unpackLocalZ(int packed) {
        return (packed >> 4) & 15;
    }

    public void onNeighborChunkLoaded(Direction side) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Direction8 dir : Direction8.values()) {
            IntSet set = blocksNeedingUpdate.get(dir);
            if (set != null && dir.getDirections().contains(side)) {
                boolean allNeighborsLoaded = true;
                for (Direction requiredDir : dir.getDirections()) {
                    if (requiredDir != side && chunk.getLevel().getChunk(chunk.getPos().x + requiredDir.getStepX(), chunk.getPos().z + requiredDir.getStepZ(), ChunkStatus.FULL, false) == null) {
                        allNeighborsLoaded = false;
                        break;
                    }
                }

                if (allNeighborsLoaded) {
                    IntIterator itr = set.iterator();
                    while (itr.hasNext()) {
                        int packed = itr.nextInt();
                        pos.set(chunk.getPos().getMinBlockX() + unpackLocalX(packed), unpackLocalY(packed), chunk.getPos().getMinBlockZ() + unpackLocalZ(packed));
                        connector.fix(worldView, pos, worldView.getBlockState(pos).getBlock());
                    }
                    blocksNeedingUpdate.remove(dir);
                }
            }
        }
    }

    public void onBlockChange(BlockPos pos, Block newBlock, boolean updateNeighbors) {
        if (updateBlock(pos, newBlock) || updateNeighbors) {
            for (Direction dir : Direction.values()) {
                BlockPos offsetPos = new BlockPos(
                        chunk.getPos().getMinBlockX() + (pos.getX() & 15) + dir.getStepX(),
                        pos.getY() + dir.getStepY(),
                        chunk.getPos().getMinBlockZ() + (pos.getZ() & 15) + dir.getStepZ());
                ChunkPos offsetChunkPos = new ChunkPos(offsetPos);
                ChunkAccess offsetChunk = offsetChunkPos.equals(chunk.getPos()) ? chunk : chunk.getLevel().getChunk(offsetChunkPos.x, offsetChunkPos.z, ChunkStatus.FULL, false);
                if (offsetChunk != null) {
                    ((IBlockConnectableChunk) offsetChunk).multiconnect_getChunkConnector().onBlockChange(offsetPos, offsetChunk.getBlockState(offsetPos).getBlock(), false);
                }
            }
        }
    }

    public boolean updateBlock(BlockPos pos, Block newBlock) {
        if (pos.getY() < worldView.getMinY() || pos.getY() > worldView.getMaxY()) {
            return false;
        }

        Direction8 dir = directionForPos(pos);
        if (dir == null) {
            return connector.fix(worldView, pos, newBlock);
        }

        boolean needsNeighbors = connector.needsNeighbors(newBlock);
        if (needsNeighbors) {
            boolean allChunksLoaded = true;
            for (Direction offset : dir.getDirections()) {
                int chunkX = chunk.getPos().x + offset.getStepX();
                int chunkZ = chunk.getPos().z + offset.getStepZ();
                if (chunk.getLevel().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                    allChunksLoaded = false;
                    break;
                }
            }
            if (allChunksLoaded) {
                needsNeighbors = false;
            }
        }

        if (!needsNeighbors) {
            IntSet set = blocksNeedingUpdate.get(dir);
            if (set != null) {
                set.remove(packLocalPos(pos));
                if (set.isEmpty()) {
                    blocksNeedingUpdate.remove(dir);
                }
            }
            return connector.fix(worldView, pos, newBlock);
        } else {
            blocksNeedingUpdate.computeIfAbsent(dir, k -> new IntOpenHashSet()).add(packLocalPos(pos));
            return false;
        }
    }
}
