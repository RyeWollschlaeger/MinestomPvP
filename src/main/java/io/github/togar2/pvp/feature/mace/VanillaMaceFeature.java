package io.github.togar2.pvp.feature.mace;

import io.github.togar2.pvp.events.FinalAttackEvent;
import io.github.togar2.pvp.feature.FeatureType;
import io.github.togar2.pvp.feature.RegistrableFeature;
import io.github.togar2.pvp.feature.config.DefinedFeature;
import io.github.togar2.pvp.feature.config.FeatureConfiguration;
import io.github.togar2.pvp.feature.explosion.ExplosionFeature;
import io.github.togar2.pvp.feature.fall.FallFeature;
import io.github.togar2.pvp.feature.fall.VanillaFallFeature;
import io.github.togar2.pvp.feature.item.ItemDamageFeature;
import io.github.togar2.pvp.utils.ViewUtil;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.ServerFlag;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.trait.EntityInstanceEvent;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Explosion;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.utils.PacketSendingUtils;
import net.minestom.server.utils.WeightedList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public class VanillaMaceFeature implements MaceFeature, RegistrableFeature {

    public static final DefinedFeature<VanillaMaceFeature> DEFINED = new DefinedFeature<>(
            FeatureType.MACE, VanillaMaceFeature::new,
            FeatureType.FALL, FeatureType.ATTACK_COOLDOWN, FeatureType.ENCHANTMENT
    );

    private final FeatureConfiguration featureConfiguration;

    private FallFeature fallFeature;
    private ItemDamageFeature itemDamageFeature;
    private ExplosionFeature explosionFeature;

    public VanillaMaceFeature(FeatureConfiguration featureConfiguration) {
        this.featureConfiguration = featureConfiguration;
    }

    @Override
    public void initDependencies() {
        this.fallFeature = featureConfiguration.get(FeatureType.FALL);
        this.itemDamageFeature = featureConfiguration.get(FeatureType.ITEM_DAMAGE);
        this.explosionFeature = featureConfiguration.get(FeatureType.EXPLOSION);
    }

    // TODO add support for mace enchantments
    // TODO add particles

    @Override
    public void init(EventNode<@NotNull EntityInstanceEvent> node) {
        EventNode<@NotNull FinalAttackEvent> maceNode = EventNode.event(
                "mace",
                EventFilter.from(FinalAttackEvent.class, Entity.class, FinalAttackEvent::getEntity),
                event -> event.getEntity() instanceof LivingEntity entity && entity.getItemInMainHand().material() == Material.MACE
        );

        EventNode<@NotNull FinalAttackEvent> windBurstNode = EventNode.event(
                "mace",
                EventFilter.from(FinalAttackEvent.class, Entity.class, FinalAttackEvent::getEntity),
                event -> event.getEntity() instanceof LivingEntity entity &&
                        canAttack(entity) &&
                        entity.getItemInMainHand().get(DataComponents.ENCHANTMENTS) instanceof EnchantmentList enchantmentList &&
                        enchantmentList.has(Enchantment.WIND_BURST)
        );

        // Initial attack
        maceNode.addListener(FinalAttackEvent.class, event -> {
            LivingEntity entity = (LivingEntity) event.getEntity();
            Entity target = event.getTarget();
            itemDamageFeature.damageEquipment(entity, EquipmentSlot.MAIN_HAND, 1);

            if (canAttack(entity)) {
                event.setAttackSounds(false);
                entity.setTag(VanillaFallFeature.EXTRA_FALL_PARTICLES, true); // TODO Doesn't look right in-game

                float amount = event.getBaseDamage();
                float smashDamageBonus = getSmashDamageBonus(entity);
                float densityDamageBonus = getDensityDamageBonus(entity);
                event.setBaseDamage(amount + smashDamageBonus + densityDamageBonus);

                Vec currentVel = entity.getVelocity();
                Vec newVel = currentVel.withY(0.01);
                entity.setVelocity(newVel);
                entity.sendPacketToViewersAndSelf(new EntityVelocityPacket(entity.getEntityId(), newVel));

                playMaceSmashFX(entity, target);
                areaKnockback(entity, target);
            }
        });

        windBurstNode.addListener(FinalAttackEvent.class, event -> {
            LivingEntity entity = (LivingEntity) event.getEntity();
            windBurst(entity, Objects.requireNonNull(entity.getItemInMainHand().get(DataComponents.ENCHANTMENTS)).level(Enchantment.WIND_BURST));
        });

        node.addChild(maceNode);
        node.addChild(windBurstNode);
    }

    private static final float[] WIND_BURST_MULTIPLIERS = {1.2f, 1.75f, 2.2f};
    private static final float WIND_BURST_RADIUS = 3.5f;

    protected void windBurst(LivingEntity entity, int windBurstLevel) {
        float multiplier = getMultiplier(windBurstLevel);
        float cx = (float) entity.getPosition().x();
        float cy = (float) entity.getPosition().y();
        float cz = (float) entity.getPosition().z();

        Explosion explosion = new Explosion(cx, cy, cz, WIND_BURST_RADIUS) {
            @Override
            protected List<Point> prepare(Instance instance) {
                Vec center = new Vec(getCenterX(), getCenterY(), getCenterZ());
                double diameter = WIND_BURST_RADIUS * 2.0;

                for (Entity nearby : instance.getEntities()) {
                    double distance = nearby.getPosition().distance(center);
                    if (distance >= diameter) continue;

                    if (nearby == entity) {
                        Vec current = entity.getVelocity();
                        entity.setVelocity(new Vec(current.x(), current.y() + multiplier * ServerFlag.SERVER_TICKS_PER_SECOND, current.z()));
                        continue;
                    }

                    double dx = nearby.getPosition().x() - center.x();
                    double dy = nearby.getPosition().y() + nearby.getEyeHeight() - center.y();
                    double dz = nearby.getPosition().z() - center.z();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist == 0) continue;

                    double strength = (1.0 - distance / diameter) * multiplier;
                    Vec knockback = new Vec(dx / dist * strength, dy / dist * strength, dz / dist * strength);
                    nearby.setVelocity(nearby.getVelocity().add(knockback.mul(ServerFlag.SERVER_TICKS_PER_SECOND)));
                }

                return List.of();
            }

            @Override
            public void apply(Instance instance) {
                List<Point> blocks = prepare(instance);

                ExplosionPacket packet = new ExplosionPacket(
                        new Vec(getCenterX(), getCenterY(), getCenterZ()), 0, 0, Vec.ZERO,
                        Particle.GUST_EMITTER_LARGE, SoundEvent.INTENTIONALLY_EMPTY, WeightedList.of());
                PacketSendingUtils.sendGroupedPacket(instance.getPlayers(), packet);

                ViewUtil.viewersAndSelf(entity).playSound(Sound.sound()
                        .type(SoundEvent.ENTITY_WIND_CHARGE_WIND_BURST)
                        .pitch(1.25f)
                        .build(), getCenterX(), getCenterY(), getCenterZ());

                postSend(instance, blocks);
            }
        };

        explosion.apply(entity.getInstance());
    }

    private static float getMultiplier(int level) {
        if (level <= WIND_BURST_MULTIPLIERS.length)
            return WIND_BURST_MULTIPLIERS[level - 1];
        return 1.5f + (level - 1) * 0.35f;
    }

    protected boolean canAttack(@NotNull LivingEntity entity) {
        return fallFeature.getFallDistance(entity) >= 1.5 && !entity.isFlyingWithElytra();
    }

    protected float getSmashDamageBonus(@NotNull LivingEntity entity) {
        float fallDistance = (float) fallFeature.getFallDistance(entity);
        float damage;
        if (fallDistance <= 3.0f)
            damage = 4.0f * fallDistance;
        else if (fallDistance <= 8.0f)
            damage = 12.0f + 2.0f * (fallDistance - 3.0f);
        else
            damage = 22.0f + fallDistance - 8.0f;
        return damage;
    }

    protected float getDensityDamageBonus(@NotNull LivingEntity entity) {
        EnchantmentList enchantments = entity.getItemInMainHand().get(DataComponents.ENCHANTMENTS);
        return (float) ((enchantments != null && enchantments.has(Enchantment.DENSITY) ?
                (float) enchantments.level(Enchantment.DENSITY) / 2 : 0)
                * fallFeature.getFallDistance(entity));
    }

    protected void playMaceSmashFX(@NotNull LivingEntity entity, @NotNull Entity target) {
        SoundEvent soundEvent;
        if (target.isOnGround() && fallFeature.getFallDistance(entity) <= 5.0)
            soundEvent = SoundEvent.ITEM_MACE_SMASH_GROUND;
        else if (target.isOnGround() && fallFeature.getFallDistance(entity) > 5.0)
            soundEvent = SoundEvent.ITEM_MACE_SMASH_GROUND_HEAVY;
        else
            soundEvent = SoundEvent.ITEM_MACE_SMASH_AIR;

        Pos pos = entity.getPosition();
        ViewUtil.viewersAndSelf(entity).playSound(
                Sound.sound()
                        .type(soundEvent)
                        .source(Sound.Source.PLAYER)
                        .build(),
                pos.x(), pos.y(), pos.z()
        );

        // TODO doesn't work
        Pos impactPos = target.getPosition();
        WorldEventPacket smashPacket = new WorldEventPacket(2013, impactPos, 750, false);
        ViewUtil.packetGroup(entity).sendGroupedPacket(smashPacket);
    }

    protected void areaKnockback(@NotNull LivingEntity entity, @NotNull Entity initialTarget) {
        initialTarget.getInstance().getEntityTracker().nearbyEntities(
                initialTarget.getPosition(), 3.5, EntityTracker.Target.ENTITIES, nearby -> {
                    if (nearby != initialTarget && nearby != entity) {
                        Vec direction = nearby.getPosition().sub(initialTarget.getPosition()).asVec();

                        // Risk of division by 0 without this
                        if (direction.lengthSquared() == 0) return;

                        double knockbackPower = getKnockbackPower(entity, nearby);
                        Vec knockbackVector = direction.normalize().mul(knockbackPower);

                        if (knockbackPower > 0.0) {
                            nearby.setVelocity(nearby.getVelocity().add(knockbackVector.x(), 0.7f, knockbackVector.z()));
                        }
                    }
                });
    }

    protected double getKnockbackPower(@NotNull LivingEntity entity, @NotNull Entity target) {
        int fallMultiplier = fallFeature.getFallDistance(entity) > 5.0 ? 2 : 1;
        double knockbackResistance = target instanceof LivingEntity livingTarget ? livingTarget.getAttributeValue(Attribute.KNOCKBACK_RESISTANCE) : 0;
        return (3.5 - target.getDistance(entity)) * 0.7 * fallMultiplier * (1 - knockbackResistance);
    }
}
