package io.github.togar2.pvp.feature.cooldown;

import io.github.togar2.pvp.feature.CombatFeature;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;

/**
 * Combat feature to manage a players item cooldown animation.
 */
public interface ItemCooldownFeature extends CombatFeature {
	ItemCooldownFeature NO_OP = new ItemCooldownFeature() {
		@Override
		public boolean hasCooldown(Player player, String cooldownGroup) {
			return false;
		}

        @Override
        public boolean hasCooldown(Player player, ItemStack itemStack) {
            return false;
        }

        @Override
		public void setCooldown(Player player, String cooldownGroup, int ticks) {}

        @Override
        public void setCooldown(Player player, ItemStack itemStack, int ticks) {

        }

        @Override
        public void setCooldown(Player player, ItemStack itemStack) {

        }
    };
	
	boolean hasCooldown(Player player, String cooldownGroup);

    boolean hasCooldown(Player player, ItemStack itemStack);
	
	void setCooldown(Player player, String cooldownGroup, int ticks);

    void setCooldown(Player player, ItemStack itemStack, int ticks);

    void setCooldown(Player player, ItemStack itemStack);
}
