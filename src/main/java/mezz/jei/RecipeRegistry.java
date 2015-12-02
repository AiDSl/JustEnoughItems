package mezz.jei;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Maps;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeTransferHelper;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.util.Log;
import mezz.jei.util.RecipeCategoryComparator;
import mezz.jei.util.RecipeMap;
import mezz.jei.util.StackUtil;

public class RecipeRegistry implements IRecipeRegistry {
	private final ImmutableMap<Class, IRecipeHandler> recipeHandlers;
	private final ImmutableTable<Class, String, IRecipeTransferHelper> recipeTransferHelpers;
	private final ImmutableMap<String, IRecipeCategory> recipeCategoriesMap;
	private final ImmutableListMultimap<IRecipeCategory, Object> recipesForCategories;
	private final RecipeMap recipeInputMap;
	private final RecipeMap recipeOutputMap;
	private final Set<Class> unhandledRecipeClasses = new HashSet<>();

	RecipeRegistry(@Nonnull ImmutableList<IRecipeCategory> recipeCategories, @Nonnull ImmutableList<IRecipeHandler> recipeHandlers, @Nonnull ImmutableList<IRecipeTransferHelper> recipeTransferHelpers, @Nonnull ImmutableList<Object> recipes) {
		recipeCategories = ImmutableSet.copyOf(recipeCategories).asList(); //remove duplicates
		this.recipeCategoriesMap = buildRecipeCategoriesMap(recipeCategories);
		this.recipeTransferHelpers = buildRecipeTransferHelperTable(recipeTransferHelpers);
		this.recipeHandlers = buildRecipeHandlersMap(recipeHandlers);

		RecipeCategoryComparator recipeCategoryComparator = new RecipeCategoryComparator(recipeCategories);
		this.recipeInputMap = new RecipeMap(recipeCategoryComparator);
		this.recipeOutputMap = new RecipeMap(recipeCategoryComparator);

		this.recipesForCategories = addRecipes(recipes);
	}

	private static ImmutableMap<String, IRecipeCategory> buildRecipeCategoriesMap(@Nonnull ImmutableList<IRecipeCategory> recipeCategories) {
		Map<String, IRecipeCategory> mutableRecipeCategoriesMap = new HashMap<>();
		for (IRecipeCategory recipeCategory : recipeCategories) {
			mutableRecipeCategoriesMap.put(recipeCategory.getUid(), recipeCategory);
		}
		return ImmutableMap.copyOf(mutableRecipeCategoriesMap);
	}

	private static ImmutableMap<Class, IRecipeHandler> buildRecipeHandlersMap(@Nonnull List<IRecipeHandler> recipeHandlers) {
		HashMap<Class, IRecipeHandler> mutableRecipeHandlers = Maps.newHashMap();
		for (IRecipeHandler recipeHandler : recipeHandlers) {
			if (recipeHandler == null) {
				continue;
			}

			Class recipeClass = recipeHandler.getRecipeClass();

			if (mutableRecipeHandlers.containsKey(recipeClass)) {
				throw new IllegalArgumentException("A Recipe Handler has already been registered for this recipe class: " + recipeClass.getName());
			}

			mutableRecipeHandlers.put(recipeClass, recipeHandler);
		}
		return ImmutableMap.copyOf(mutableRecipeHandlers);
	}

	private static ImmutableTable<Class, String, IRecipeTransferHelper> buildRecipeTransferHelperTable(@Nonnull List<IRecipeTransferHelper> recipeTransferHelpers) {
		ImmutableTable.Builder<Class, String, IRecipeTransferHelper> builder = ImmutableTable.builder();
		for (IRecipeTransferHelper recipeTransferHelper : recipeTransferHelpers) {
			builder.put(recipeTransferHelper.getContainerClass(), recipeTransferHelper.getRecipeCategoryUid(), recipeTransferHelper);
		}
		return builder.build();
	}

	private ImmutableListMultimap<IRecipeCategory, Object> addRecipes(@Nullable ImmutableList<Object> recipes) {
		if (recipes == null) {
			return ImmutableListMultimap.of();
		}

		ImmutableListMultimap.Builder<IRecipeCategory, Object> builder = ImmutableListMultimap.builder();

		for (Object recipe : recipes) {
			try {
				addRecipe(builder, recipe);
			} catch (RuntimeException e) {
				Log.error("Failed to add recipe: {}\nWith error: {}", recipe, e);
			}
		}

		return builder.build();
	}

	private void addRecipe(@Nonnull ImmutableListMultimap.Builder<IRecipeCategory, Object> builder, @Nullable Object recipe) {
		if (recipe == null) {
			return;
		}

		Class recipeClass = recipe.getClass();
		IRecipeHandler recipeHandler = getRecipeHandler(recipeClass);
		if (recipeHandler == null) {
			if (!unhandledRecipeClasses.contains(recipeClass)) {
				unhandledRecipeClasses.add(recipeClass);
				Log.debug("Can't handle recipe: {}", recipeClass);
			}
			return;
		}

		String recipeCategoryUid = recipeHandler.getRecipeCategoryUid();
		IRecipeCategory recipeCategory = recipeCategoriesMap.get(recipeCategoryUid);
		if (recipeCategory == null) {
			Log.error("No recipe category registered for recipeCategoryUid: {}", recipeCategoryUid);
			return;
		}

		//noinspection unchecked
		if (!recipeHandler.isRecipeValid(recipe)) {
			return;
		}

		//noinspection unchecked
		IRecipeWrapper recipeWrapper = recipeHandler.getRecipeWrapper(recipe);

		List inputs = recipeWrapper.getInputs();
		List<FluidStack> fluidInputs = recipeWrapper.getFluidInputs();
		if (inputs != null || fluidInputs != null) {
			if (recipeWrapper.usesOreDictionaryComparison()) {
				inputs = StackUtil.expandRecipeInputs(inputs, true);
			}
			List<ItemStack> inputStacks = StackUtil.toItemStackList(inputs);
			if (fluidInputs == null) {
				fluidInputs = Collections.emptyList();
			}
			recipeInputMap.addRecipe(recipe, recipeCategory, inputStacks, fluidInputs);
		}

		List outputs = recipeWrapper.getOutputs();
		List<FluidStack> fluidOutputs = recipeWrapper.getFluidOutputs();
		if (outputs != null || fluidOutputs != null) {
			List<ItemStack> outputStacks = StackUtil.toItemStackList(outputs);
			if (fluidOutputs == null) {
				fluidOutputs = Collections.emptyList();
			}
			recipeOutputMap.addRecipe(recipe, recipeCategory, outputStacks, fluidOutputs);
		}

		builder.put(recipeCategory, recipe);
	}

	@Nonnull
	@Override
	public ImmutableList<IRecipeCategory> getRecipeCategories() {
		return ImmutableList.copyOf(recipeCategoriesMap.values());
	}

	@Nullable
	@Override
	public IRecipeHandler getRecipeHandler(@Nonnull Class recipeClass) {
		IRecipeHandler recipeHandler;
		while ((recipeHandler = recipeHandlers.get(recipeClass)) == null && (recipeClass != Object.class)) {
			recipeClass = recipeClass.getSuperclass();
		}

		return recipeHandler;
	}

	@Nonnull
	@Override
	public ImmutableList<IRecipeCategory> getRecipeCategoriesWithInput(@Nullable ItemStack input) {
		if (input == null) {
			return ImmutableList.of();
		}
		return recipeInputMap.getRecipeCategories(input);
	}

	@Nonnull
	@Override
	public ImmutableList<IRecipeCategory> getRecipeCategoriesWithInput(@Nullable Fluid input) {
		if (input == null) {
			return ImmutableList.of();
		}
		return recipeInputMap.getRecipeCategories(input);
	}

	@Nonnull
	@Override
	public ImmutableList<IRecipeCategory> getRecipeCategoriesWithOutput(@Nullable ItemStack output) {
		if (output == null) {
			return ImmutableList.of();
		}
		return recipeOutputMap.getRecipeCategories(output);
	}

	@Nonnull
	@Override
	public ImmutableList<IRecipeCategory> getRecipeCategoriesWithOutput(@Nullable Fluid output) {
		if (output == null) {
			return ImmutableList.of();
		}
		return recipeOutputMap.getRecipeCategories(output);
	}

	@Nonnull
	@Override
	public ImmutableList<Object> getRecipesWithInput(@Nullable IRecipeCategory recipeCategory, @Nullable ItemStack input) {
		if (recipeCategory == null || input == null) {
			return ImmutableList.of();
		}
		return recipeInputMap.getRecipes(recipeCategory, input);
	}

	@Nonnull
	@Override
	public ImmutableList<Object> getRecipesWithInput(@Nullable IRecipeCategory recipeCategory, @Nullable Fluid input) {
		if (recipeCategory == null || input == null) {
			return ImmutableList.of();
		}
		return recipeInputMap.getRecipes(recipeCategory, input);
	}

	@Nonnull
	@Override
	public ImmutableList<Object> getRecipesWithOutput(@Nullable IRecipeCategory recipeCategory, @Nullable ItemStack output) {
		if (recipeCategory == null || output == null) {
			return ImmutableList.of();
		}
		return recipeOutputMap.getRecipes(recipeCategory, output);
	}

	@Nonnull
	@Override
	public ImmutableList<Object> getRecipesWithOutput(@Nullable IRecipeCategory recipeCategory, @Nullable Fluid output) {
		if (recipeCategory == null || output == null) {
			return ImmutableList.of();
		}
		return recipeOutputMap.getRecipes(recipeCategory, output);
	}

	@Nonnull
	@Override
	public ImmutableList<Object> getRecipes(@Nullable IRecipeCategory recipeCategory) {
		if (recipeCategory == null) {
			return ImmutableList.of();
		}
		return recipesForCategories.get(recipeCategory);
	}

	@Nullable
	@Override
	public IRecipeTransferHelper getRecipeTransferHelper(@Nullable Container container, @Nullable IRecipeCategory recipeCategory) {
		if (container == null || recipeCategory == null) {
			return null;
		}
		return recipeTransferHelpers.get(container.getClass(), recipeCategory.getUid());
	}
}
