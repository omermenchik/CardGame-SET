package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.ArrayList;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected Semaphore tableSem;
    protected final Integer[] cardToSlot; // slot per card (if any)
    protected int[][] playerTokenLocations;
    protected int[] slotAvailability;
    protected AtomicBoolean lockTable = new AtomicBoolean(false);
    protected volatile int [] tokensCounterPerPlayer;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        slotAvailability = new int[slotToCard.length];
        tokensCounterPerPlayer = new int[env.config.players];
        playerTokenLocations = new int[env.config.players][3];
        tableSem = new Semaphore(1, true);
        for (int i = 0; i < playerTokenLocations.length; i++) {
            for (int j = 0; j < 3; j++) {
                playerTokenLocations[i][j] = -1;
            }
        }
        for (int i = 0; i < slotAvailability.length; i++){
            slotAvailability[i] = 0;                 
        }
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }


    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
        if (cardToSlot[card] == null) {
            cardToSlot[card] = slot;
            slotToCard[slot] = card;
            env.ui.placeCard(card, slot);
        }

    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        int removeCard = slotToCard[slot];
        cardToSlot[removeCard] = null;
        slotToCard[slot] = null;
        slotAvailability[slot] = 0;
        env.ui.removeCard(slot);
    }

    public void placeToken(int player, int slot) {
        boolean acquired = false;
        try {
            tableSem.acquire(); 
            acquired = true; 
            boolean tokenPlaced = false;
            for (int i = 0; i < 3 && !tokenPlaced; i++) {
                if (playerTokenLocations[player][i] == -1) { 
                    playerTokenLocations[player][i] = slot;
                    env.ui.placeToken(player, slot);
                    tokenPlaced = true; // Token placed
                    tokensCounterPerPlayer[player]++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); 
        } finally {
            if (acquired) {
                tableSem.release(); 
            }
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        boolean removed = false;
        try {
            tableSem.acquire();    
            for (int i = 0; i < 3; i++) {
                if (playerTokenLocations[player][i] == slot) {
                    env.ui.removeToken(player, slot);
                    playerTokenLocations[player][i] = -1;
                    tokensCounterPerPlayer[player]--;
                    removed = true;
                    break; 
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); 
        } finally {
            tableSem.release();
        }
        return removed;
    }
    
    public final int[] getPlayerTokens(int playerId) {
        return playerTokenLocations[playerId];
    }

    public void removeSet(int[] set) {
        for (int i = 0; i < set.length; i++) {
            for (int j = 0; j < playerTokenLocations.length; j++) {  
                removeToken(j, cardToSlot[set[i]]);
            }
        }
        List<Integer> removeIndexes = new ArrayList<>(3);
        for (int n = 0; n < set.length; n++) { 
            removeIndexes.add(cardToSlot[set[n]]);
        }
        Collections.shuffle(removeIndexes);
        for (Integer index : removeIndexes) { 
            removeCard(index);
        }
    }

    public void removeAllTokens() {
        for (int i = 0; i < playerTokenLocations.length ; i++) {
            for (int j = 0; j < 3; j++) {
                playerTokenLocations[i][j] = -1;
                tokensCounterPerPlayer[i]--;
            }
        }
        env.ui.removeTokens();
    }
}