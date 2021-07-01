package nl.dgoossens.autocraft.helpers;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import nl.dgoossens.autocraft.AutomatedCrafting;
import nl.dgoossens.autocraft.api.CraftingRecipe;
import nl.dgoossens.autocraft.api.Pair;
import nl.dgoossens.autocraft.api.RecipeType;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * Build a recipe from item stacks in code.
 * These recipes have a prebuilt list of items they search for that
 * can be re-used for both testing if the items are contained in the
 * inventory as for taking them.
 */
public class BukkitRecipe implements CraftingRecipe {
    private RecipeType type = RecipeType.UNKNOWN;
    private final ItemStack result;
    private List<RecipeRequirement> requirements;

    //Shaped Recipes
    private String[] pattern;
    private Map<Character, Collection<ItemStack>> key;

    //Shapeless Recipes
    private Collection<Collection<ItemStack>> ingredients;

    private static final Class<?> craftMetaBlockState = ReflectionHelper.getCraftBukkitClass("inventory.CraftMetaBlockState").orElse(null);
    private static final Field blockEntityTag = ReflectionHelper.getField(craftMetaBlockState, "blockEntityTag").orElse(null);

    public BukkitRecipe(ItemStack result, String[] pattern, Map<Character, Collection<ItemStack>> key) {
        type = RecipeType.SHAPED;
        this.result = result;
        this.pattern = pattern;
        this.key = key;
    }

    public BukkitRecipe(ItemStack result, List<Collection<ItemStack>> ingredients) {
        type = RecipeType.SHAPELESS;
        this.result = result;
        this.ingredients = ingredients;
    }

    /**
     * Build a recipe from a bukkit recipe.
     */
    public BukkitRecipe(Recipe bukkitRecipe) {
        result = bukkitRecipe.getResult();
        if (bukkitRecipe instanceof ShapedRecipe) {
            type = RecipeType.SHAPED;
            pattern = ((ShapedRecipe) bukkitRecipe).getShape();
            key = new HashMap<>();

            Map<Character, RecipeChoice> choiceMap = ((ShapedRecipe) bukkitRecipe).getChoiceMap();
            choiceMap.forEach((k, v) -> {
                List<ItemStack> values = new ArrayList<>();
                if (v != null) { //V can be null for some reason.
                    if (v instanceof RecipeChoice.ExactChoice) {
                        values.addAll(((RecipeChoice.ExactChoice) v).getChoices());
                    } else if (v instanceof RecipeChoice.MaterialChoice) {
                        for (Material m : ((RecipeChoice.MaterialChoice) v).getChoices()) {
                            values.add(new ItemStack(m));
                        }
                    } else {
                        ItemStack val = v.getItemStack();
                        if (val != null) values.add(val);
                    }
                }
                key.put(k, values);
            });
        } else if (bukkitRecipe instanceof ShapelessRecipe) {
            type = RecipeType.SHAPELESS;
            ingredients = new ArrayList<>();

            List<RecipeChoice> choiceList = ((ShapelessRecipe) bukkitRecipe).getChoiceList();
            for (var v : choiceList) {
                List<ItemStack> values = new ArrayList<>();
                if (v != null) { //V can be null for some reason.
                    if (v instanceof RecipeChoice.ExactChoice) {
                        values.addAll(((RecipeChoice.ExactChoice) v).getChoices());
                    } else if (v instanceof RecipeChoice.MaterialChoice) {
                        for (Material m : ((RecipeChoice.MaterialChoice) v).getChoices()) {
                            values.add(new ItemStack(m));
                        }
                    } else {
                        ItemStack val = v.getItemStack();
                        if (val != null) values.add(val);
                    }
                }
                ingredients.add(values);
            }
        }
    }

    public RecipeType getType() {
        return type;
    }

    /**
     * The requirements map for this recipe, can be cached.
     */
    private List<RecipeRequirement> getRequirements() {
        if (requirements == null) {
            requirements = new ArrayList<>();
            switch (type) {
                case SHAPED -> {
                    //Count how many times each character in the pattern occurrences
                    Map<Character, Integer> occurrences = new HashMap<>();
                    for (String s : pattern) {
                        for (char c : s.toCharArray()) {
                            occurrences.put(c, occurrences.getOrDefault(c, 0) + 1);
                        }
                    }

                    // Test if all characters in the pattern show up in the recipe
                    occurrences.forEach((c, i) -> {
                        if (!key.containsKey(c)) {
                            AutomatedCrafting.getInstance().warning("Warning shaped recipe with pattern [[" + String.join("], [", pattern) + "]] had character " + c + " in pattern but not in key map.");
                        }
                    });

                    // Put the corresponding item for each part of the shape into the requirements list,
                    // we multiply the requirement for the amount of times the character occurs in the pattern.
                    key.forEach((c, items) -> {
                        if (!occurrences.containsKey(c)) {
                            AutomatedCrafting.getInstance().warning("Warning shaped recipe with pattern [[" + String.join("], [", pattern) + "]] had key " + c + " in key map but not in pattern.");
                        }

                        requirements.add(new RecipeRequirement(items, occurrences.getOrDefault(c, 0)));
                    });
                }
                case SHAPELESS -> ingredients.forEach(i -> requirements.add(new RecipeRequirement(i, 1)));
            }

            // Remove empty requirements
            requirements.removeIf(RecipeRequirement::isInvalid);
        }
        return requirements;
    }

    @Override
    public boolean containsRequirements(Inventory inv) {
        var solutions = Collections.singletonList(new RequirementSolution(inv));
        for (var requirement : getRequirements()) {
            // Get all new permutations of the solutions with this new requirement
            solutions = requirement.getSolutions(solutions);

            // If there are ever no new solutions that means the requirement
            // cannot be fulfilled so the recipe is not possible
            if (solutions.isEmpty()) return false;
        }

        // If we didn't already return due to the there not being any solutions
        // there are valid ingredients present.
        return true;
    }

    @Override
    public List<ItemStack> takeMaterials(Inventory inv) {
        var solutions = Collections.singletonList(new RequirementSolution(inv));
        for (var requirement : getRequirements()) {
            // Get all new permutations of the solutions with this new requirement
            solutions = requirement.getSolutions(solutions);

            // If there are no solutions [containsRequirements] should have failed.
            if (solutions.isEmpty()) {
                throw new UnsupportedOperationException("Recipe is being crafted without necessary materials, how?");
            }
        }

        // Get the cheapest solution or the only solution if there is one (which is the case in most recipes)
        var finalSolution = solutions.size() == 1 ? solutions.get(0) :
                solutions.stream().min(Comparator.comparing(RequirementSolution::getCost)).orElseThrow(() -> new UnsupportedOperationException("No solutions found, how?"));

        // Take all items and return the created container items
        finalSolution.applyTo(inv);
        return finalSolution.getContainerItems();
    }

    @Override
    public boolean creates(ItemStack stack) {
        var clone = stack.clone();

        // For all block state meta items we clear the block entity tag off the item we use for comparisons
        // so a full shulker box is accepted as craftable
        if (clone.hasItemMeta() && clone.getItemMeta() instanceof BlockStateMeta meta) {
            if (blockEntityTag != null) {
                try {
                    blockEntityTag.set(meta, null);
                } catch (Exception x) {
                    x.printStackTrace();
                }
            }
            clone.setItemMeta(meta);
        }

        return isSimilar(result, clone);
    }

    @Override
    public ItemStack getResultDrop() {
        return result.clone();
    }

    public static class RequirementSolution {
        private final List<Pair<RecipeRequirement, ItemStack>> history = new ArrayList<>();
        private final List<ItemStack> containerItems = new ArrayList<>();
        private final ItemStack[] state;

        public RequirementSolution(Inventory base) {
            state = new ItemStack[base.getStorageContents().length];
            for (int j = 0; j < state.length; j++) {
                // Build clones so we don't affect the original inventory
                if (base.getStorageContents()[j] != null)
                    state[j] = base.getStorageContents()[j].clone();
            }
        }

        private RequirementSolution(RequirementSolution old) {
            // Copy the history
            history.addAll(old.history);

            // Copy the state
            state = new ItemStack[old.state.length];
            for (int j = 0; j < state.length; j++) {
                // Build clones so we don't affect the original state
                if (old.state[j] != null)
                    state[j] = old.state[j].clone();
            }

            // Copy the container items
            // We don't clone them to save on performance as we never modify them
            // anyways, we only use the ones from the final solution even.
            containerItems.addAll(old.containerItems);
        }

        /**
         * Returns the list of container items created by this solution.
         */
        public List<ItemStack> getContainerItems() {
            return containerItems;
        }

        /**
         * Returns a new permutation of this solution with the given requirement's solution of item.
         */
        public RequirementSolution addRequirement(RecipeRequirement requirement, ItemStack item) {
            var newSol = new RequirementSolution(this);
            newSol.history.add(new Pair<>(requirement, item));

            // We need to take the amount of times we need this requirement times the amount in the requirement
            int amountToFind = requirement.amount * item.getAmount();

            // Go through the state of the new requirement and remove the items
            for (int j = 0; j < newSol.state.length; j++) {
                var it = newSol.state[j];

                // If this item is similar we start taking it away
                if (isSimilar(item, it)) {
                    int cap = Math.min(it.getAmount(), amountToFind);
                    if (it.getAmount() - cap <= 0) newSol.state[j] = null;
                    else it.setAmount(it.getAmount() - cap);
                    amountToFind -= cap;

                    // If we're taking any items we see if that leaves a container item
                    if (cap >= 0) {
                        var containerItem = getContainerItem(item.getType(), cap);
                        if (containerItem != null) {
                            containerItems.add(containerItem);
                        }
                    }

                    // When we've taken all the items that need taking we're done
                    if (amountToFind <= 0) break;
                }
            }

            return newSol;
        }

        /**
         * Tests if this solution has the items required to fulfill the given variant of the requirement.
         */
        public boolean test(RecipeRequirement requirement, ItemStack item) {
            // We need to find the amount of times we need this requirement times the amount in the requirement
            int amountToFind = requirement.amount * item.getAmount();

            for (ItemStack it : state) {
                //If any item in our array of valid items is similar to this item we have found our match.
                if (isSimilar(item, it)) {
                    amountToFind -= Math.min(it.getAmount(), amountToFind);

                    //If we have at least the amount of any valid item in this inventory we call it good.
                    if (amountToFind <= 0)
                        return true;
                }
            }

            return false;
        }

        /**
         * Returns the amount of items that were used for this solution.
         */
        public int getCost() {
            return history.stream().mapToInt(it -> it.getKey().amount * it.getValue().getAmount()).sum();
        }

        /**
         * Applies this solution to the given inventory.
         */
        public void applyTo(Inventory inv) {
            for (int i = 0; i < state.length; i++) {
                inv.getStorageContents()[i] = state[i];
            }
        }

        /**
         * Get the 'container item' which is the item
         * left in the crafting area after an item is used
         * in a crafting recipe.
         * <p>
         * Returns null if nothing/air is the container item.
         */
        private static ItemStack getContainerItem(Material input, int amount) {
            var remainingItem = input.getCraftingRemainingItem();
            if (remainingItem == null) return null;
            return new ItemStack(remainingItem, amount);
        }
    }

    /**
     * A single recipe requirement that may have multiple valid items to fulfill it.
     * {@link #getSolutions(Collection<RequirementSolution>)} returns a list of RequirementSolutions that fulfill this requirement.
     */
    public static class RecipeRequirement {
        private final Collection<ItemStack> item;
        private final int amount;

        public RecipeRequirement(Collection<ItemStack> items, int amount) {
            this.item = items;
            this.amount = amount;
        }

        /**
         * Returns whether this recipe requirement is invalid or not. Invalid requirements should not be used ever.
         */
        public boolean isInvalid() {
            return item == null || item.isEmpty() || amount < 1;
        }

        /**
         * Returns a list of all solutions that can validate this requirement.
         */
        public List<RequirementSolution> getSolutions(Collection<RequirementSolution> solutions) {
            return solutions.stream().flatMap(base -> item.stream().filter(it -> base.test(this, it)).map(it -> base.addRequirement(this, it))).collect(Collectors.toList());
        }
    }

    /**
     * Custom isSimilar implementation that supports ingredients with a
     * durability of -1.
     */
    public static boolean isSimilar(ItemStack a, ItemStack b) {
        // Documentation is a bit vague but it appears ingredients with -1 mean
        // the metadata isn't important and it should accept any type. We always
        // pass the ingredient as a so if a has a durability of -1 we only compare
        // materials. (Bukkit changes -1 to Short.MAX_VALUE)
        if (a != null && b != null && a.getDurability() == Short.MAX_VALUE) {
            return a.getType() == b.getType();
        }
        return a != null && a.isSimilar(b);
    }
}
