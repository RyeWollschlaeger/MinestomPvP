package io.github.togar2.pvp.feature.projectile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.metadata.LivingEntityMeta;
import net.minestom.server.event.item.PlayerBeginItemUseEvent;
import net.minestom.server.event.item.PlayerCancelItemUseEvent;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.togar2.pvp.entity.projectile.AbstractArrow;
import io.github.togar2.pvp.entity.projectile.Arrow;
import io.github.togar2.pvp.entity.projectile.SpectralArrow;
import io.github.togar2.pvp.feature.FeatureType;
import io.github.togar2.pvp.feature.RegistrableFeature;
import io.github.togar2.pvp.feature.config.DefinedFeature;
import io.github.togar2.pvp.feature.config.FeatureConfiguration;
import io.github.togar2.pvp.feature.effect.EffectFeature;
import io.github.togar2.pvp.feature.enchantment.EnchantmentFeature;
import io.github.togar2.pvp.feature.item.ItemDamageFeature;
import io.github.togar2.pvp.utils.ViewUtil;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerTickEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.event.trait.EntityInstanceEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.tag.Tag;

/**
 * Vanilla implementation of {@link CrossbowFeature}
 */
public class VanillaCrossbowFeature implements CrossbowFeature, RegistrableFeature {
    public static final DefinedFeature<VanillaCrossbowFeature> DEFINED = new DefinedFeature<>(
            FeatureType.CROSSBOW, VanillaCrossbowFeature::new,
            FeatureType.ITEM_DAMAGE, FeatureType.EFFECT, FeatureType.ENCHANTMENT, FeatureType.PROJECTILE_ITEM
    );

    private static final Tag<@NotNull Boolean> START_SOUND_PLAYED = Tag.Transient("StartSoundPlayed");
    private static final Tag<@NotNull Boolean> MID_LOAD_SOUND_PLAYED = Tag.Transient("MidLoadSoundPlayed");
    private static final Tag<@NotNull Boolean> JUST_FINISHED_LOADING = Tag.Boolean("JustFinishedLoading");
    private static final Tag<@NotNull Boolean> JUST_SHOT = Tag.Boolean("JustShot");

    private final FeatureConfiguration configuration;

    private ItemDamageFeature itemDamageFeature;
    private EffectFeature effectFeature;
    private EnchantmentFeature enchantmentFeature;
    private ProjectileItemFeature projectileItemFeature;

    public VanillaCrossbowFeature(FeatureConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void initDependencies() {
        this.itemDamageFeature = configuration.get(FeatureType.ITEM_DAMAGE);
        this.effectFeature = configuration.get(FeatureType.EFFECT);
        this.enchantmentFeature = configuration.get(FeatureType.ENCHANTMENT);
        this.projectileItemFeature = configuration.get(FeatureType.PROJECTILE_ITEM);
    }

    @Override
    public void init(EventNode<@NotNull EntityInstanceEvent> node) {

        // Player Loading the crossbow
        node.addListener(PlayerBeginItemUseEvent.class, event -> {
            var itemStack = event.getItemStack();
            var player = event.getPlayer();
            if (itemStack.material() != Material.CROSSBOW) return;
            if (projectileItemFeature.getCrossbowProjectile(player) == null && player.getGameMode() != GameMode.CREATIVE) {
                event.setCancelled(true);
                return;
            }

            if (player.getTag(JUST_SHOT) != null && player.getTag(JUST_SHOT) == true) {
                itemStack = setCrossbowProjectile(itemStack, List.of());
                player.setItemInHand(event.getHand(), itemStack);
            }

            if (!isCrossbowCharged(itemStack)) {
                event.setItemUseDuration(getCrossbowUseDuration(itemStack));
            }
            event.getPlayer().setTag(JUST_SHOT, false);
        });

        node.addListener(PlayerTickEvent.class, event -> {
            Player player = event.getPlayer();
            var meta = (LivingEntityMeta) player.getEntityMeta();

            // Check if player is actively charging a crossbow
            if (!meta.isHandActive()) return;

            PlayerHand hand = meta.getActiveHand();
            ItemStack stack = player.getItemInHand(hand);

            if (stack.material() != Material.CROSSBOW) return;
            if (isCrossbowCharged(stack)) return; // Already loaded

            // Calculate progress based on use time
            long useTicks = player.getCurrentItemUseTime();
            int chargeDuration = getCrossbowChargeDuration(stack);
            double progress = useTicks / (double) chargeDuration;

            int quickCharge = Objects.requireNonNull(stack.get(DataComponents.ENCHANTMENTS)).level(Enchantment.QUICK_CHARGE);

            Boolean startSoundPlayed = player.getTag(START_SOUND_PLAYED);
            if (progress >= 0.2 && (startSoundPlayed == null || !startSoundPlayed)) {
                SoundEvent startSound = getCrossbowStartSound(quickCharge);
                ViewUtil.viewersAndSelf(player).playSound(Sound.sound(
                        startSound, Sound.Source.PLAYER,
                        0.5f, 1.0f
                ), player);
                player.setTag(START_SOUND_PLAYED, true);
            }

            Boolean midLoadSoundPlayed = player.getTag(MID_LOAD_SOUND_PLAYED);
            SoundEvent midLoadSound = quickCharge == 0 ? SoundEvent.ITEM_CROSSBOW_LOADING_MIDDLE : null;
            if (progress >= 0.5 && midLoadSound != null && (midLoadSoundPlayed == null || !midLoadSoundPlayed)) {
                ViewUtil.viewersAndSelf(player).playSound(Sound.sound(
                        midLoadSound, Sound.Source.PLAYER,
                        0.5f, 1.0f
                ), player);
                player.setTag(MID_LOAD_SOUND_PLAYED, true);
            }
        });

        // Loading crossbow with projectile
        node.addListener(PlayerFinishItemUseEvent.class, event -> {
            var player = event.getPlayer();
            var itemStack = event.getItemStack();
            if (itemStack.material() != Material.CROSSBOW) return;
            if (!isCrossbowCharged(itemStack)) {
                var chargedCrossbow = loadCrossbowProjectiles(player, itemStack);
                player.setTag(JUST_FINISHED_LOADING, true);
                player.setItemInHand(event.getHand(), chargedCrossbow);
                event.getInstance().playSound(Sound.sound()
                                .type(SoundEvent.ITEM_CROSSBOW_LOADING_END)
                                .build(),
                        event.getPlayer().getPosition());
                player.removeTag(START_SOUND_PLAYED);
                player.removeTag(MID_LOAD_SOUND_PLAYED);
            }
        });

        // Forces you to let go of RMB to shoot the crossbow
        node.addListener(PlayerCancelItemUseEvent.class, event -> {
            var player = event.getPlayer();
            var itemStack = event.getItemStack();

            // Removes these tags so it plays the sound again if the player stopped loading the crossbow mid-load
            player.removeTag(START_SOUND_PLAYED);
            player.removeTag(MID_LOAD_SOUND_PLAYED);

            if (itemStack.material() != Material.CROSSBOW) return;
            if (player.getTag(JUST_FINISHED_LOADING) == null || player.getTag(JUST_FINISHED_LOADING) == false) return;
            player.setTag(JUST_FINISHED_LOADING, false);
        });

        // Shooting projectile from crossbow
        node.addListener(PlayerUseItemEvent.class, event -> {
            var player = event.getPlayer();
            var itemStack = event.getItemStack();
            if (itemStack.material() != Material.CROSSBOW) return;
            if (!isCrossbowCharged(itemStack) || player.getTag(JUST_FINISHED_LOADING) == true) return;

            performCrossbowShooting(player, event.getHand(), itemStack, 3, 0.1);
            player.setTag(JUST_SHOT, true);

            var unchargedCrossbow = setCrossbowProjectile(itemStack, List.of());
            player.setItemInHand(event.getHand(), unchargedCrossbow);
            player.clearItemUse();
        });
    }

    protected AbstractArrow createArrow(ItemStack stack, @Nullable Entity shooter) {
        if (stack.material() == Material.SPECTRAL_ARROW) {
            return new SpectralArrow(shooter, enchantmentFeature);
        } else {
            Arrow arrow = new Arrow(shooter, effectFeature, enchantmentFeature);
            arrow.setItemStack(stack);
            return arrow;
        }
    }

    protected boolean isCrossbowCharged(ItemStack stack) {
        return stack.has(DataComponents.CHARGED_PROJECTILES) &&
                !Objects.requireNonNull(stack.get(DataComponents.CHARGED_PROJECTILES)).isEmpty();
    }

    protected ItemStack setCrossbowProjectile(ItemStack stack, @NotNull List<ItemStack> projectiles) {
        return stack.with(DataComponents.CHARGED_PROJECTILES, projectiles);
    }

    protected ItemStack setCrossbowProjectile(ItemStack stack, @NotNull ItemStack projectile) {
        return stack.with(DataComponents.CHARGED_PROJECTILES,  List.of(projectile));
    }

    protected boolean crossbowContainsProjectile(ItemStack stack, Material projectile) {
        List<ItemStack> projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles == null) return false;

        for (ItemStack itemStack : projectiles) {
            if (itemStack.material() == projectile) return true;
        }

        return false;
    }

    protected int getCrossbowUseDuration(ItemStack stack) {
        return getCrossbowChargeDuration(stack) + 3;
    }

    protected int getCrossbowChargeDuration(ItemStack stack) {
        int quickCharge = Objects.requireNonNull(stack.get(DataComponents.ENCHANTMENTS)).level(Enchantment.QUICK_CHARGE);
        return quickCharge == 0 ? 25 : 25 - 5 * quickCharge;
    }

    protected SoundEvent getCrossbowStartSound(int quickCharge) {
        return switch (quickCharge) {
            case 1 -> SoundEvent.ITEM_CROSSBOW_QUICK_CHARGE_1;
            case 2 -> SoundEvent.ITEM_CROSSBOW_QUICK_CHARGE_2;
            case 3 -> SoundEvent.ITEM_CROSSBOW_QUICK_CHARGE_3;
            default -> SoundEvent.ITEM_CROSSBOW_LOADING_START;
        };
    }

    protected ItemStack loadCrossbowProjectiles(Player player, ItemStack stack) {
        int multiShot = Objects.requireNonNull(stack.get(DataComponents.ENCHANTMENTS)).level(Enchantment.MULTISHOT);

        ItemStack projectileItem;
        int projectileSlot;

        ProjectileItemFeature.ProjectileItem projectile = projectileItemFeature.getCrossbowProjectile(player);
        if (projectile == null && player.getGameMode() == GameMode.CREATIVE) {
            projectileItem = Arrow.DEFAULT_ARROW;
            projectileSlot = -1;
        } else if (projectile != null) {
            projectileItem = projectile.stack();
            projectileSlot = projectile.slot();
        } else {
            // Should not happen
            return ItemStack.of(Material.PUMPKIN);
        }

        ArrayList<ItemStack> projectiles = new ArrayList<>(List.of(projectileItem));
        if (multiShot > 0) {
            for (int i = 0; i < multiShot; i++) {
                projectiles.add(projectileItem);
                projectiles.add(projectileItem);
            }
        }
        stack = setCrossbowProjectile(stack, projectiles);

        if (player.getGameMode() != GameMode.CREATIVE && projectileSlot >= 0) {
            player.getInventory().setItemStack(projectileSlot, projectileItem.withAmount(projectileItem.amount() - 1));
        }

        return stack;
    }

    protected void performCrossbowShooting(Player player, PlayerHand hand, ItemStack stack,
                                           double power, double spread) {
        List<ItemStack> projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles == null || projectiles.isEmpty()) return;

        var offsetYaw = 0.0f;
        for (int turn = 0; turn < projectiles.size(); turn++) {
            var projectile = projectiles.get(turn);

            if (turn == 0) {
                shootCrossbowProjectile(player, hand, stack, projectile, 1, power, spread, offsetYaw);
                offsetYaw+=10.0f;
            } else {
                if (turn % 2 == 1) {
                    shootCrossbowProjectile(player, hand, stack, projectile, 1, power, spread, offsetYaw);
                }
                if (turn % 2 == 0) {
                    shootCrossbowProjectile(player, hand, stack, projectile, 1, power, spread, -offsetYaw);
                    offsetYaw+=10.0f;
                }
            }
        }

        setCrossbowProjectile(stack, List.of());
    }

    protected void shootCrossbowProjectile(Player player, PlayerHand hand, ItemStack crossbowStack,
                                           ItemStack projectile, float soundPitch,
                                           double power, double spread, float yaw) {
        boolean firework = projectile.material() == Material.FIREWORK_ROCKET;
        if (firework) return; //TODO firework

        AbstractArrow arrow = getCrossbowArrow(player, crossbowStack, projectile);
        if (player.getGameMode() == GameMode.CREATIVE || yaw != 0.0) {
            arrow.setPickupMode(AbstractArrow.PickupMode.CREATIVE_ONLY);
        }

        Pos position = player.getPosition().add(0, player.getEyeHeight() - 0.1, 0);

        arrow.shootFromRotation(position.pitch(), position.yaw() + yaw, 0, power, spread);
        Vec playerVel = player.getVelocity();
        arrow.setVelocity(arrow.getVelocity().add(playerVel.x(),
                player.isOnGround() ? 0.0D : playerVel.y(), playerVel.z()));
        arrow.setInstance(Objects.requireNonNull(player.getInstance()), position);

        var direction = player.getPosition().direction().normalize();
        arrow.setView(
                (float) Math.toDegrees(Math.atan2(direction.x(), direction.z())),
                (float) Math.toDegrees(Math.asin(direction.y()))
        );

        itemDamageFeature.damageEquipment(player, hand == PlayerHand.MAIN ?
                EquipmentSlot.MAIN_HAND : EquipmentSlot.OFF_HAND, firework ? 3 : 1);

        ViewUtil.viewersAndSelf(player).playSound(Sound.sound(
                SoundEvent.ITEM_CROSSBOW_SHOOT, Sound.Source.PLAYER,
                1.0f, soundPitch
        ), player);
    }

    protected AbstractArrow getCrossbowArrow(Player player, ItemStack crossbowStack, ItemStack projectile) {
        AbstractArrow arrow = createArrow(projectile.withAmount(1), player);
        arrow.setCritical(true); // Player shooter is always critical
        arrow.setSound(SoundEvent.ITEM_CROSSBOW_HIT);

        int piercing = Objects.requireNonNull(crossbowStack.get(DataComponents.ENCHANTMENTS)).level(Enchantment.PIERCING);
        if (piercing > 0) {
            arrow.setPiercingLevel((byte) piercing);
        }

        return arrow;
    }

}

