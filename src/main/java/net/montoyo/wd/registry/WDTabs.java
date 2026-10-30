/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.registry;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;

public class WDTabs {
	public static final CreativeModeTab EXAMPLE_TAB = new CreativeModeTab("webdisplays") {
		@Override
		public ItemStack makeIcon() {
			return new ItemStack(ItemRegistry.SCREEN.get());
		}
		
		@Override
		public void fillItemList(NonNullList<ItemStack> output) {
			// core items
			add(output, ItemRegistry.SCREEN);
			add(output, ItemRegistry.KEYBOARD);
			add(output, ItemRegistry.LINKER);
			// remote control
			add(output, ItemRegistry.REMOTE_CONTROLLER);
			// redstone stuff
			add(output, ItemRegistry.REDSTONE_CONTROLLER);
			// admin tools
			add(output, ItemRegistry.OWNERSHIP_THEIF);
			// tool items
			add(output, ItemRegistry.SERVER);
			add(output, ItemRegistry.CONFIGURATOR);
			add(output, ItemRegistry.MINEPAD);
			add(output, ItemRegistry.LASER_POINTER);
			
			// upgrades
			for (int i = 0; i < ItemRegistry.countUpgrades(); i++) add(output, ItemRegistry.getUpgradeItem(i));
			// cc
			for (int i = 0; i < ItemRegistry.countCompCraftItems(); i++) add(output, ItemRegistry.getComputerCraftItem(i));
		}
		
		private static void add(NonNullList<ItemStack> output, RegistryObject<? extends Item> item) {
			output.add(new ItemStack(item.get()));
		}
	};
	
	public static void init(IEventBus bus) {
		// 1.19.2 creative tabs are not registry objects; the tab registers itself on construction
	}
}
