package net.clayborn.accurateblockplacement.mixin;

import net.clayborn.accurateblockplacement.AccurateBlockPlacementMod;
import net.clayborn.accurateblockplacement.IKeyBindingAccessor;
import net.clayborn.accurateblockplacement.IMinecraftClientAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin
{
	@Unique
	private static final String blockActivateMethodName = getBlockActivateMethodName();
	@Unique
	private static final String itemUseMethodName = getItemUseMethodName();
	@Unique
	private BlockPos lastSeenBlockPos = null;
	@Unique
	private BlockPos lastPlacedBlockPos = null;
	@Unique
	private Vec3d lastPlayerPlacedBlockPos = null;
	@Unique
	private Boolean autoRepeatWaitingOnCooldown = true;
	@Unique
	private Vec3d lastFreshPressMouseRatio = null;
	@Unique
	private ArrayList<HitResult> backFillList = new ArrayList<>();
	@Unique
	private Item lastItemInUse = null;

	@Unique
	Hand handOfCurrentItemInUse;

	@Unique
	private Item getItemInUse(MinecraftClient client)
	{
		// have to check each hand
		Hand[] hands = Hand.values();
		int numHands = hands.length;

		for(int i = 0; i < numHands; ++i) {
			Hand thisHand = hands[i];
			assert client.player!= null;
			ItemStack itemInHand = client.player.getStackInHand(thisHand);

			if(itemInHand.isEmpty()) {
				// hand is empty try the next one
				continue;
			}
			else {
				handOfCurrentItemInUse = thisHand;
				return itemInHand.getItem();
			}
		}

		return null;
	}

	@Unique
	private static String getBlockActivateMethodName()
	{
		Method[] methods = Block.class.getMethods();

		for(Method method : methods) {
			Class<?>[] types = method.getParameterTypes();

			if(types.length != 6) {
				continue;
			}
			if(types[0] != BlockState.class) {
				continue;
			}
			if(types[1] != World.class) {
				continue;
			}
			if(types[2] != BlockPos.class) {
				continue;
			}
			if(types[3] != PlayerEntity.class) {
				continue;
			}
			if(types[4] != Hand.class) {
				continue;
			}
			if(types[5] != BlockHitResult.class) {
				continue;
			}
			return method.getName();
		}
		return null;
	}

	@Unique
	private static String getItemUseMethodName()
	{
		try {
			Method useMethod = Item.class.getDeclaredMethod("use", World.class, PlayerEntity.class, Hand.class);
			return useMethod.getName();
		}
		catch (NoSuchMethodException e) {
			return null;
		}
	}

	@Unique
	private static boolean doesBlockHaveOverriddenActivateMethod(Block block)
	{
		if(blockActivateMethodName == null) {
			return false;
		}

		try {
			Method activateMethod = block.getClass().getDeclaredMethod(blockActivateMethodName, BlockState.class, World.class, BlockPos.class, PlayerEntity.class, Hand.class, BlockHitResult.class);
			return activateMethod.getDeclaringClass()!= Block.class;
		}
		catch (NoSuchMethodException e) {
			return false;
		}

	}

	@Unique
	private static boolean doesItemHaveOverriddenUseMethod(Item item)
	{
		if(itemUseMethodName == null) {
			return false;
		}

		try {
			Method useMethod = item.getClass().getDeclaredMethod(itemUseMethodName, ItemStack.class, World.class, PlayerEntity.class, Hand.class, BlockHitResult.class);
			return useMethod.getDeclaringClass()!= Item.class;
		}
		catch (NoSuchMethodException e) {
			return false;
		}
	}

	@Inject(at = @org.spongepowered.asm.mixin.injection.At("HEAD"), method = "render")
	private void render(CallbackInfo info)
	{
		if(!AccurateBlockPlacementMod.isAccurateBlockPlacementEnabled) {
			// reset all state just in case
			AccurateBlockPlacementMod.disableNormalItemUse = false;

			lastSeenBlockPos = null;
			lastPlacedBlockPos = null;
			lastPlayerPlacedBlockPos = null;

			autoRepeatWaitingOnCooldown = true;
			backFillList.clear();

			lastFreshPressMouseRatio = null;

			lastItemInUse = null;

			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();

		// safety checks
		if(client == null || client.options == null || client.options.useKey == null || client.crosshairTarget == null || client.player == null || client.world == null || client.mouse == null || client.getWindow() == null) {
			return;
		}

		// will be set to true only if needed
		AccurateBlockPlacementMod.disableNormalItemUse = false;
		IKeyBindingAccessor keyUseAccessor = (IKeyBindingAccessor) client.options.useKey;
		boolean freshKeyPress = keyUseAccessor.accurateblockplacement_GetTimesPressed() > 0;

		Item currentItem = getItemInUse(client);

		// reset state if the key was actually pressed
		// note: at very low frame rates they might have let go and hit it again before
		// we get back here
		if(freshKeyPress) {
			// clear history since they let go of the button
			lastSeenBlockPos = null;
			lastPlacedBlockPos = null;
			lastPlayerPlacedBlockPos = null;

			autoRepeatWaitingOnCooldown = true;
			backFillList.clear();

			if(client.getWindow().getWidth() > 0 && client.getWindow().getHeight() > 0) {
				lastFreshPressMouseRatio = new Vec3d(client.mouse.getX() / client.getWindow().getWidth(), client.mouse.getY() / client.getWindow().getHeight(), 0);
			}
			else {
				lastFreshPressMouseRatio = null;
			}

			// a fresh keypress is required each time the item being used changes
			lastItemInUse = currentItem;
		}

		// if nothing in hand, let vanilla take over
		if(currentItem == null) {
			return;
		}

		// if the item isn't a block or a mining tool (axe, hoe, pickaxe, shovel), let vanilla take over
		if(!(currentItem instanceof BlockItem) && !(currentItem instanceof MiningToolItem)) {
			return;
		}

		// TODO: Add if the item we are holding is activatable, let vanilla take over

        // if we aren't looking at a block (so we can place), let vanilla take over
		if(client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
			return;
		}

		// check the other hand if it has something in use and if so let vanilla take over
		Hand otherHand = handOfCurrentItemInUse == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
		ItemStack otherHandItemStack = client.player.getStackInHand(otherHand);
		if(!otherHandItemStack.isEmpty() && (doesItemHaveOverriddenUseMethod(otherHandItemStack.getItem())) && client.player.isUsingItem()) {
			return;
		}

		BlockHitResult blockHitResult = (BlockHitResult) client.crosshairTarget;
		BlockPos blockHitPos = blockHitResult.getBlockPos();
		Block targetBlock = client.world.getBlockState(blockHitPos).getBlock();
		boolean isTargetBlockActivatable = doesBlockHaveOverriddenActivateMethod(targetBlock);

		// don't override behavior of clicking activatable blocks (and stairs) unless holding SNEAKING to replicate vanilla behaviors
		if(isTargetBlockActivatable && !(targetBlock instanceof StairsBlock) && !client.player.isSneaking()) {
			return;
		}

		// are they holding the use key and is the item to use a block?
		// also is the SAME item we started with if we are in repeat mode?
		// note: check both freshKey and current state in cause of shitty frame rates
		if((freshKeyPress || client.options.useKey.isPressed())) {
			// it's a block!! it's go time!
			AccurateBlockPlacementMod.disableNormalItemUse = true;

			ItemPlacementContext targetPlacement = new ItemPlacementContext(new ItemUsageContext(client.player, handOfCurrentItemInUse, blockHitResult));

			// remember what was there before
			Block oldBlock = client.world.getBlockState(targetPlacement.getBlockPos()).getBlock();

			double facingAxisPlayerPos = 0.0d;
			double facingAxisPlayerLastPos = 0.0d;
			double facingAxisLastPlacedPos = 0.0d;

			if(lastPlacedBlockPos != null && lastPlayerPlacedBlockPos != null) {
				Axis axis = targetPlacement.getSide().getAxis();

				facingAxisPlayerPos = client.player.getPos().getComponentAlongAxis(axis);
				facingAxisPlayerLastPos = lastPlayerPlacedBlockPos.getComponentAlongAxis(axis);
				facingAxisLastPlacedPos = new Vec3d(lastPlacedBlockPos.getX(), lastPlacedBlockPos.getY(), lastPlacedBlockPos.getZ()).getComponentAlongAxis(axis);

				// fixes placement being directional because getting the correct side pos is apparently too hard
				if(targetPlacement.getSide().getName().equals("west") || targetPlacement.getSide().getName().equals("north")) {
					facingAxisLastPlacedPos += 1.0d;
				}
			}

			IMinecraftClientAccessor clientAccessor = (IMinecraftClientAccessor) client;

			Vec3d currentMouseRatio = null;

			if(client.getWindow().getWidth() > 0 && client.getWindow().getHeight() > 0) {
				currentMouseRatio = new Vec3d(client.mouse.getX() / client.getWindow().getWidth(), client.mouse.getY() / client.getWindow().getHeight(), 0);
			}

			// Condition:
			// [ [ we have a fresh key press ] OR
			// [ [ we have no 'seen' history or the 'seen' history isn't a match ] AND
			// [ we have no 'place' history or the 'place' history isn't a match ] ] OR
			// [ we have 'place' history, it is a match, the player is building toward
			// themselves and has moved one block backwards] ]
			boolean isPlacementTargetFresh = ((lastSeenBlockPos == null || !lastSeenBlockPos.equals(blockHitPos))
				&& (lastPlacedBlockPos == null || !lastPlacedBlockPos.equals(blockHitPos)))
				|| (lastPlacedBlockPos != null && lastPlayerPlacedBlockPos != null
					&& lastPlacedBlockPos.equals(blockHitPos)
					&& Math.abs(facingAxisPlayerLastPos - facingAxisPlayerPos) >= 0.99d // because precision
					&& Math.abs(facingAxisPlayerLastPos - facingAxisLastPlacedPos) < Math.abs(facingAxisPlayerPos - facingAxisLastPlacedPos));

			boolean hasMouseMoved = (currentMouseRatio != null && lastFreshPressMouseRatio != null && lastFreshPressMouseRatio.distanceTo(currentMouseRatio) >= 0.1);

			boolean isOnCooldown = autoRepeatWaitingOnCooldown && clientAccessor.accurateblockplacement_GetItemUseCooldown() > 0 && !hasMouseMoved;

			// if [ we are still holding the same block we start pressing 'use' with] AND
			// [ [ this is a fresh keypress ] OR
			// [ [ we have a fresh place to put a block ] AND
			// [ auto-repeat isn't on cooldown OR the mouse has moved enough ] ]
			// we can try to place a block
			// note: this is always true on a fresh keypress
			if(lastItemInUse == currentItem) {
				if(freshKeyPress || (isPlacementTargetFresh && !isOnCooldown)) {
					// update if we are repeating
					if(autoRepeatWaitingOnCooldown && !freshKeyPress) {
						autoRepeatWaitingOnCooldown = false;

						HitResult currentHitResult = client.crosshairTarget;

						// try to place the backlog
						for(HitResult prevHitResult : backFillList)	{
							client.crosshairTarget = prevHitResult;
							// use item
							clientAccessor.accurateblockplacement_DoItemUseBypassDisable();
						}

						backFillList.clear();

						client.crosshairTarget = currentHitResult;
					}

					// always run at least once if we reach here
					// if this isn't a fresh key press, turn on the run once flag
					boolean runOnceFlag = !freshKeyPress;

					// in case they manage to push the button multiple times per frame
					// note: we already subtracted one from the press count earlier so the total
					// should be the same
					while(runOnceFlag || client.options.useKey.wasPressed()) {
						// use item
						clientAccessor.accurateblockplacement_DoItemUseBypassDisable();

						// update last placed
						if(!oldBlock.equals(client.world.getBlockState(targetPlacement.getBlockPos()).getBlock())) {
							lastPlacedBlockPos = targetPlacement.getBlockPos();

							if(lastPlayerPlacedBlockPos == null) {
								lastPlayerPlacedBlockPos = client.player.getPos();
							}
							else {
								// prevent slow rounding error from eventually moving the player out of range
								Vec3d summedLastPlayerPos = lastPlayerPlacedBlockPos.add(new Vec3d(targetPlacement.getSide().getVector().getX(), targetPlacement.getSide().getVector().getY(), targetPlacement.getSide().getVector().getZ()));

								Vec3d newLastPlayerPlacedPos = switch (targetPlacement.getSide().getAxis()) {
                                    case X ->
                                            new Vec3d(summedLastPlayerPos.x, client.player.getPos().y, client.player.getPos().z);
                                    case Y ->
                                            new Vec3d(client.player.getPos().x, summedLastPlayerPos.y, client.player.getPos().z);
                                    case Z ->
                                            new Vec3d(client.player.getPos().x, client.player.getPos().y, summedLastPlayerPos.z);
                                };

                                lastPlayerPlacedBlockPos = newLastPlayerPlacedPos;
							}
						}

						runOnceFlag = false;
					}
				}
				else if(isPlacementTargetFresh) {
					// populate the backfill list just in case
					backFillList.add(client.crosshairTarget);
				}
			}

			// update the last block we looked at
			lastSeenBlockPos = blockHitResult.getBlockPos();
		}
	}
}
