package bguspl.set.ex;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;
    public AtomicBoolean playerSetValidationComplete = new AtomicBoolean(false);
    public AtomicBoolean playerIsReadyForAction = new AtomicBoolean(false);
    public AtomicInteger playerSetResult = new AtomicInteger(0);
    protected ArrayBlockingQueue nextclicks;
    private Dealer dealer;
    private Boolean frozen;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.score = 0;
        this.dealer = dealer;
        this.nextclicks = new ArrayBlockingQueue(3); // the queue of keys pressed by the player
        this.frozen=false;
        
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            if (human) {
                synchronized (nextclicks) {
                    if (!terminate && nextclicks.isEmpty()) {
                        try {
                            this.nextclicks.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            try {
                performAction(); //place or delete a token
                checkPlayerSet(); 
            } catch (InterruptedException ignored) {}
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                keyPressed((new Random()).nextInt(env.config.tableSize));
                synchronized (aiThread) {
                    while ((nextclicks.size() == 3)&&!terminate) {
                        try {
                            aiThread.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }
    

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate=true;
        if(!human){
            this.aiThread.interrupt();
        }
        this.playerThread.interrupt();
    }


    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {

        while (nextclicks.size()==3) {
            synchronized (nextclicks) {
                nextclicks.notifyAll();
            }
        }
        if (human)
            synchronized (nextclicks) {
                nextclicks.notifyAll();
            }
        if (nextclicks.size()<3 && table.lockTable.get()&&!frozen) {
            nextclicks.add(slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        synchronized(this) {
            score = score + 1;
            env.ui.setScore(id, score);   
        }
        long startfreeze = System.currentTimeMillis();
        this.frozen=true;
        while (env.config.pointFreezeMillis - (System.currentTimeMillis() - startfreeze) >= 0) {
            env.ui.setFreeze(id, env.config.pointFreezeMillis - (System.currentTimeMillis() - startfreeze) + 1000);
        }
        playerSetValidationComplete.set(false);
        env.ui.setFreeze(id, 0);
        this.frozen=false;
    }
    
    // /**
    //  * Penalize a player and perform other related actions.
    //  */
    public void penalty() {
        synchronized(this) {
            this.frozen=true;
            long startfreeze = System.currentTimeMillis();
            while (env.config.penaltyFreezeMillis - (System.currentTimeMillis() - startfreeze) >= 1) {
                env.ui.setFreeze(id, env.config.penaltyFreezeMillis - (System.currentTimeMillis() - startfreeze) + 1000);
            }
            env.ui.setFreeze(id, 0);
            this.frozen=false;
            playerIsReadyForAction.set(true);
        }
    }    


    public int score() {
        return score;
    }

    public void resetPlayerActions() {
        this.nextclicks.clear();;
        playerSetValidationComplete.set(false);
        if (!human)
            synchronized (aiThread) {
                aiThread.notifyAll();
            }
        synchronized (this) {
            this.notifyAll();
        }
        playerIsReadyForAction.set(true);
    }
    
     public synchronized void performAction() throws InterruptedException {
        if (!nextclicks.isEmpty() && table.lockTable.get() && playerIsReadyForAction.get()) { // editing the set
            int action = (int) nextclicks.take();
            if (!human){
                synchronized (aiThread) {
                    aiThread.notifyAll();
                }
            }
            if (table.slotToCard[action] != null) {
                if(table.slotToCard[action] != -1){
                    boolean tokenRemoved = table.removeToken(id, action);
                    if (tokenRemoved) {  
                        playerSetValidationComplete.set(false);
                    } else                                        
                        table.placeToken(id,action);
                }
            }
        }
    }
    
    public void checkPlayerSet() throws InterruptedException {
        if (playerIsReadyForAction.get() && table.lockTable.get() && table.tokensCounterPerPlayer[id]==3) {
            int[] playerSet = new int[3];
            for (int i = 0; i < playerSet.length; i++) {
                playerSet[i] = table.getPlayerTokens(id)[i];
            }
            boolean existSet = playerSet[0]!= -1 && playerSet[1]!= -1 && playerSet[2]!= -1;
            if (existSet && !playerSetValidationComplete.get()) //complete a possible set
            {
                playerSetValidationComplete.set(true);
                playerIsReadyForAction.set(false);
                dealer.dealerSem.acquire();
                dealer.playerIdForPotentialSets.offer(id);
                synchronized(this){
                    this.notifyAll();
                }
                dealer.dealerSem.release();
                synchronized (this) {
                    while (!playerIsReadyForAction.get())
                        wait();
                }
            }
            if (playerSetResult.get() == 1) {
                point();

            } else if (playerSetResult.get() == -1) {
                penalty();
            }
        }
    }
}