package com.bertramlabs.plugins.karman.util;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * BoundedExecutor
 *
 * Using a Semaphore to throttle task submission
 *
 * @author Brian Goetz and Tim Peierls
 */
public class BoundedExecutor {
    public final Executor exec;
    private final Semaphore semaphore;
    public BoundedExecutor(Executor exec, int bound) {
        this.exec = exec;
        this.semaphore = new Semaphore(bound);
    }
    public void submit(final Runnable command)
            throws InterruptedException {
        semaphore.acquire();
        try {
            exec.execute(new Runnable() {
                public void run() {
                    try {
                        command.run();
                    } finally {
                        semaphore.release();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            semaphore.release();
        }
    }

    public void submit(final Callable command)
            throws InterruptedException {
        semaphore.acquire();
        try {
            exec.execute(new Runnable() {
                public void run() {
                    try {
                        command.call();
                    } catch(Exception ex) {
                        ex.printStackTrace();
                        //ignore it silently
                    } finally {
                        semaphore.release();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            semaphore.release();
        }
    }

    public int availablePermits() {
        return this.semaphore.availablePermits();
    }
}
