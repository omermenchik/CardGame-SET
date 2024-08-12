package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static java.util.Collections.shuffle;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    public long cycleBeginTime;
    protected Semaphore dealerSem;
    public BlockingQueue<Integer> playerIdForPotentialSets;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        dealerSem = new Semaphore(1, true);
        playerIdForPotentialSets = new LinkedBlockingDeque<>(players.length);
    }


    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        startPlayersThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            if(env.config.hints){
                table.hints();
            }
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        Thread.currentThread().interrupt();
        terminate = true;
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        try{
            long leftTime = env.config.turnTimeoutMillis - (System.currentTimeMillis()- cycleBeginTime);
            if (!playerIdForPotentialSets.isEmpty() && leftTime>0) {
                int currentSetPlayerId = playerIdForPotentialSets.take();
                int[] playerSlots =  table.playerTokenLocations[currentSetPlayerId];
                int[] playerCards = new int[3];
                for (int i = 0; i < 3; i++){
                    if(playerSlots[i]==-1)
                    {
                        players[currentSetPlayerId].playerSetResult.set(0); 
                        players[currentSetPlayerId].playerIsReadyForAction.set(true);
                        synchronized (players[currentSetPlayerId]) {
                            players[currentSetPlayerId].notifyAll();
                        }
                        return;
                    }
                    playerCards[i] = table.slotToCard[playerSlots[i]];
                }
                int isLegalSet = isLegalSet(playerCards);
                if (isLegalSet == 1) 
                {
                    table.lockTable.set(false);
                    table.removeSet(playerCards);
                }
                players[currentSetPlayerId].playerSetResult.set(isLegalSet); 
                players[currentSetPlayerId].playerIsReadyForAction.set(true);
                synchronized (players[currentSetPlayerId]) {
                    players[currentSetPlayerId].notifyAll();
                }
            }
        }
            catch (InterruptedException ignored) { 
        }
    }
    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        table.lockTable.set(false);
        List<Integer> avaliableIndexes = new ArrayList<>();
        for (int i = 0; i < table.slotAvailability.length; i++) {
            if (table.slotAvailability[i] == 0) {
                avaliableIndexes.add(i);
                table.slotAvailability[i] = 1;
            }
        }
        if (avaliableIndexes.size() > 0) {
            shuffle(avaliableIndexes);
            shuffle(deck);
            while (deck.size() > 0 && !avaliableIndexes.isEmpty()){
                Integer cardFromDeck = deck.remove(deck.size() - 1);
                Integer availableIndex = avaliableIndexes.remove(avaliableIndexes.size() - 1);
                table.placeCard(cardFromDeck, availableIndex);
            }
            updateTimerDisplay(true);
        }
        table.lockTable.set(true);
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    
     private void sleepUntilWokenOrTimeout() {
        if (env.config.turnTimeoutMillis > 0) {
            while (cycleBeginTime > System.currentTimeMillis() && playerIdForPotentialSets.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    System.out.println(e.toString());
                }
                env.ui.setCountdown(cycleBeginTime - System.currentTimeMillis(), (cycleBeginTime - System.currentTimeMillis()) <= 10000 && cycleBeginTime - System.currentTimeMillis() > 0);
            }
        }
        else if(env.config.turnTimeoutMillis == 0){
            while (playerIdForPotentialSets.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    System.out.println(e.toString());
                }
                env.ui.setCountdown(System.currentTimeMillis() - cycleBeginTime,false);
            }
        }
        else{
            while (playerIdForPotentialSets.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    System.out.println(e.toString());
                }
            }
        }
    }
    
    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            cycleBeginTime = System.currentTimeMillis();
            reshuffleTime = cycleBeginTime + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis+999, false);
        } else {
            long delta = env.config.turnTimeoutMillis - (System.currentTimeMillis() - cycleBeginTime);
            if (delta > env.config.turnTimeoutWarningMillis)
                env.ui.setCountdown(delta+999, false);
            else {
                delta = Math.max(0,delta);
                env.ui.setCountdown(delta, true);
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        table.lockTable.set(false);
        table.removeAllTokens();
        playerIdForPotentialSets.clear();
        for (int i = 0; i < table.tokensCounterPerPlayer.length; i++) {
            table.tokensCounterPerPlayer[i] = 0;
        }
        ArrayList<Integer> cardSlots = new ArrayList<>();
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i] != null) {
                cardSlots.add(i);
            }
        }
        shuffle(cardSlots);
        for (Integer slotIndex : cardSlots) {
            int card = table.slotToCard[slotIndex];
            table.removeCard(slotIndex);
            deck.add(card);
            env.ui.removeCard(slotIndex);
        }
        
        for (Player player : players) {
            player.resetPlayerActions();
        }
    }
    

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int numOfWinners = 0;
        int highestScore = 0;
        for (Player player : players) {
            if (player.score() > highestScore) {
                highestScore = player.score();
            }
        }
        for (Player player : players) {
            if (player.score() == highestScore) {
                numOfWinners++;
            }
        }
        int i=0;
        int[] winners = new int[numOfWinners];
        for (Player player : players) {
            if (player.score() == highestScore) {
                winners[i] = player.id;
                i++;
            }
        }
        env.ui.announceWinner(winners);
    }

    private boolean SetExist(int[] cardIndices) {
        for (int index : cardIndices) {
            if (null == table.cardToSlot[index]) return false;
        }
        return true;
    }


    public int isLegalSet(int[] candidateSet) {
        boolean setExists = SetExist(candidateSet);
        boolean isSetValid = env.util.testSet(candidateSet);
        
        if (setExists && isSetValid) return 1;
        if (!setExists && isSetValid) return 0;
        return -1;
    }

    private void startPlayersThread() {
        Thread[] threads = new Thread[players.length];
        for (int i = 0; i < players.length; i++) {
            threads[i] = new Thread(players[i]);
        }
        for (int i = 0; i < players.length; i++) {
            threads[i].start();
        }
        for (Player player : players) {
            player.playerIsReadyForAction.set(true);
        }
    }
}