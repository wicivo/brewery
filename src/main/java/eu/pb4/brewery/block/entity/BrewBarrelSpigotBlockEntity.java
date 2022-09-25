package eu.pb4.brewery.block.entity;

import eu.pb4.brewery.BreweryInit;
import eu.pb4.brewery.drink.DrinkType;
import eu.pb4.brewery.drink.DrinkUtils;
import eu.pb4.brewery.drink.ExpressionUtil;
import eu.pb4.brewery.item.BrewItems;
import eu.pb4.brewery.item.IngredientMixtureItem;
import eu.pb4.brewery.other.BrewGameRules;
import eu.pb4.sgui.api.gui.SimpleGui;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Iterator;

public final class BrewBarrelSpigotBlockEntity extends LootableContainerBlockEntity implements TickableContents {
    private final LongSet parts = new LongArraySet();
    private DefaultedList<ItemStack> inventory;
    private String barrelType = "void";
    private long lastTicked = -1;

    public BrewBarrelSpigotBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(BrewBlockEntities.BARREL_SPIGOT, blockPos, blockState);
        this.inventory = DefaultedList.ofSize(27, ItemStack.EMPTY);
    }

    public static <T extends BlockEntity> void ticker(World world, BlockPos pos, BlockState state, T t) {
        if (!(t instanceof BrewBarrelSpigotBlockEntity)) {
            return;
        }

        var barrel = (BrewBarrelSpigotBlockEntity) t;

        var currentTime = world.getTime();


        if (barrel.lastTicked == -1) {
            barrel.lastTicked = world.getTime();
            return;
        }

        var agingMultiplier = world.getGameRules().get(BrewGameRules.BARREL_AGING_MULTIPLIER).get();

        barrel.tickContents(world.getGameRules().getBoolean(BrewGameRules.AGE_UNLOADED) ? (currentTime - barrel.lastTicked) * agingMultiplier : agingMultiplier);

        barrel.lastTicked = currentTime;
    }

    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        if (!this.serializeLootTable(nbt)) {
            Inventories.writeNbt(nbt, this.inventory);
        }

        nbt.put("Parts", new NbtLongArray(this.parts));
        nbt.putString("BarrelType", this.barrelType);
        nbt.putLong("LastTicked", this.lastTicked);
    }

    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        this.inventory = DefaultedList.ofSize(this.size(), ItemStack.EMPTY);
        if (!this.deserializeLootTable(nbt)) {
            Inventories.readNbt(nbt, this.inventory);
        }

        this.parts.clear();
        for (var part : nbt.getLongArray("Parts")) {
            this.parts.add(part);
        }

        this.barrelType = nbt.getString("BarrelType");
        this.lastTicked = nbt.getLong("LastTicked");
    }

    public void tickContents(double l) {
        for (int i = 0; i < this.size(); i++) {
            var stack = this.getStack(i);
            if (!stack.isEmpty()) {
                if (stack.isOf(BrewItems.DRINK_ITEM)) {
                    var type = DrinkUtils.getType(stack);
                    if (type != null) {
                        var barrelInfo = type.getBarrelInfo(this.barrelType);

                        if (barrelInfo != null) {
                            stack.getOrCreateNbt().putString(DrinkUtils.BARREL_TYPE_NBT, this.barrelType);
                            var currentAge = DrinkUtils.getAgeInTicks(stack, -barrelInfo.baseTime());
                            var newAge = currentAge + l;

                            stack.getNbt().putDouble(DrinkUtils.AGE_NBT, newAge);

                            var mult = DrinkUtils.getIngredientMultiplier(stack);

                            if (newAge >= 0) {
                                var quality = barrelInfo.qualityChange().expression()
                                        .setVariable(ExpressionUtil.QUALITY_KEY, type.baseQuality().expression().setVariable(ExpressionUtil.AGE_KEY, newAge / 20d).evaluate())
                                        .setVariable(ExpressionUtil.AGE_KEY, newAge / 20d)
                                        .evaluate() * mult;

                                if (quality >= 0) {
                                    stack.getNbt().putDouble(DrinkUtils.QUALITY_NBT, Math.min(quality, 10));
                                } else {
                                    this.setStack(i, new ItemStack(BrewItems.FAILED_DRINK_ITEM));
                                }
                            }
                        }
                    }
                } else if (stack.isOf(BrewItems.INGREDIENT_MIXTURE)) {
                    var age = DrinkUtils.getAgeInTicks(stack, 0) + l;
                    var ageSec = age / 20d;

                    var ingredients = IngredientMixtureItem.getIngredients(stack);
                    var types = DrinkUtils.findTypes(ingredients, this.barrelType);

                    if (types.isEmpty()) {
                        this.setStack(i, new ItemStack(BrewItems.FAILED_DRINK_ITEM));
                    } else {
                        double quality = Double.MIN_VALUE;
                        DrinkType match = null;
                        boolean isMatchGeneric = true;

                        for (var type : types) {
                            var barrelInfo = type.getBarrelInfo(this.barrelType);
                            var generic = barrelInfo.type().equals("*");
                            if (ageSec >= barrelInfo.baseTime() && (!generic || isMatchGeneric)) {
                                var newAge = ageSec - barrelInfo.baseTime();

                                var q = barrelInfo.qualityChange().expression()
                                        .setVariable(ExpressionUtil.QUALITY_KEY, type.baseQuality().expression().setVariable(ExpressionUtil.AGE_KEY, newAge).evaluate())
                                        .setVariable(ExpressionUtil.AGE_KEY, newAge)
                                        .evaluate();

                                if (q > quality || generic != isMatchGeneric) {
                                    quality = q;
                                    match = type;
                                    isMatchGeneric = generic;
                                }
                            }
                        }

                        if (match == null) {
                            stack.getOrCreateNbt().putDouble(DrinkUtils.AGE_NBT, age);
                        } else if (quality < 0) {
                            this.setStack(i, new ItemStack(BrewItems.FAILED_DRINK_ITEM));
                        } else {
                            this.setStack(i, DrinkUtils.createDrink(BreweryInit.DRINK_TYPE_ID.get(match),
                                    match.cookingQualityMult().expression().setVariable("age", stack.getNbt().getDouble(DrinkUtils.AGE_COOK_NBT) / 20d).evaluate()));
                        }
                    }
                }
            }
        }
    }

    @Override
    protected Text getContainerName() {
        return Text.translatable("container.brewery." + barrelType + "_barrel");
    }

    @Override
    protected ScreenHandler createScreenHandler(int syncId, PlayerInventory playerInventory) {
        return null;
    }

    @Override
    protected DefaultedList<ItemStack> getInvStackList() {
        return this.inventory;
    }

    @Override
    protected void setInvStackList(DefaultedList<ItemStack> list) {
        this.inventory = list;
    }

    @Override
    public int size() {
        return 27;
    }

    public void addPart(BlockPos pos) {
        this.parts.add(pos.asLong());
        this.markDirty();
    }

    public LongSet getParts() {
        return this.parts;
    }

    public Iterable<BlockPos.Mutable> iterableParts() {
        var pos = new BlockPos.Mutable();
        return () -> new Iterator<>() {
            final LongIterator iter = BrewBarrelSpigotBlockEntity.this.parts.iterator();

            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public BlockPos.Mutable next() {
                var val = iter.nextLong();
                return pos.set(BlockPos.unpackLongX(val), BlockPos.unpackLongY(val), BlockPos.unpackLongZ(val));
            }
        };
    }

    public void setBarrelType(String type) {
        this.barrelType = type;
    }

    public void openGui(ServerPlayerEntity player) {
        new Gui(player);
    }

    private class Gui extends SimpleGui {
        public Gui(ServerPlayerEntity player) {
            super(ScreenHandlerType.GENERIC_9X3, player, false);
            this.setTitle(BrewBarrelSpigotBlockEntity.this.getDisplayName());

            for (int i = 0; i < BrewBarrelSpigotBlockEntity.this.size(); i++) {
                this.setSlotRedirect(i, new Slot(BrewBarrelSpigotBlockEntity.this, i, 0, 0) {
                    @Override
                    public boolean canInsert(ItemStack stack) {
                        if (stack.isOf(BrewItems.DRINK_ITEM)) {

                            var type = DrinkUtils.getType(stack);
                            var barrelType = DrinkUtils.getBarrelType(stack);
                            return super.canInsert(stack) && type != null && !type.barrelInfo().isEmpty() &&
                                    (barrelType.isEmpty() || barrelType.equals(BrewBarrelSpigotBlockEntity.this.barrelType) || DrinkUtils.getAgeInTicks(stack) <= 0);
                        } else if (stack.isOf(BrewItems.INGREDIENT_MIXTURE)) {
                            return true;
                        } else {
                            return false;
                        }
                    }

                    @Override
                    public int getMaxItemCount() {
                        return 1;
                    }

                    @Override
                    public int getMaxItemCount(ItemStack stack) {
                        return 1;
                    }
                });
            }
            this.open();
        }


        @Override
        public void onTick() {
            if (BrewBarrelSpigotBlockEntity.this.isRemoved()
                    || BrewBarrelSpigotBlockEntity.this.getPos().getSquaredDistanceFromCenter(this.player.getX(), this.player.getY(), this.player.getZ()) > 20*20) {
                this.close();
            }

            super.onTick();
        }
    }
}