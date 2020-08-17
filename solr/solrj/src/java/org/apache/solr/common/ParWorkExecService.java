package org.apache.solr.common;

import org.apache.solr.common.util.CloseTracker;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.TimeOut;
import org.apache.solr.common.util.TimeSource;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ParWorkExecService extends AbstractExecutorService {
  private static final Logger log = LoggerFactory
      .getLogger(MethodHandles.lookup().lookupClass());

  private static final int MAX_AVAILABLE = Math.max(ParWork.PROC_COUNT, 3);
  private final Semaphore available = new Semaphore(MAX_AVAILABLE, false);

  private final ExecutorService service;
  private final int maxSize;
  private volatile boolean terminated;
  private volatile boolean shutdown;

  private final BlockingArrayQueue<Runnable> workQueue = new BlockingArrayQueue<>(30, 0);
  private volatile Worker worker;
  private volatile Future<?> workerFuture;

  private class Worker extends Thread {

    Worker() {
      setName("ParExecWorker");
    }

    @Override
    public void run() {
      while (!terminated) {
        Runnable runnable = null;
        try {
          runnable = workQueue.poll(5, TimeUnit.SECONDS);
          //System.out.println("get " + runnable + " " + workQueue.size());
        } catch (InterruptedException e) {
//          ParWork.propegateInterrupt(e);
           continue;
        }
        if (runnable == null) {
          continue;
        }
        //        boolean success = checkLoad();
//        if (success) {
//          success = available.tryAcquire();
//        }
//        if (!success) {
//          runnable.run();
//          return;
//        }
        if (runnable instanceof ParWork.SolrFutureTask) {

        } else {

          try {
            available.acquire();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }

        }

        Runnable finalRunnable = runnable;
        service.execute(new Runnable() {
          @Override
          public void run() {
            try {
              finalRunnable.run();
            } finally {
              try {
                if (finalRunnable instanceof ParWork.SolrFutureTask) {

                } else {
                  available.release();
                }
              } finally {
                ParWork.closeExecutor();
              }
            }
          }
        });
      }
    }
  }

  public ParWorkExecService(ExecutorService service) {
    this(service, -1);
  }


  public ParWorkExecService(ExecutorService service, int maxSize) {
    assert service != null;
    assert ObjectReleaseTracker.track(this);
    if (maxSize == -1) {
      this.maxSize = MAX_AVAILABLE;
    } else {
      this.maxSize = maxSize;
    }
    this.service = service;
  }

  @Override
  protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
    return new FutureTask(runnable, value);
  }

  @Override
  protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
    if (callable instanceof ParWork.NoLimitsCallable) {
      return (RunnableFuture) new ParWork.SolrFutureTask(callable);
    }
    return new FutureTask(callable);
  }

  @Override
  public void shutdown() {

    this.shutdown = true;
   // worker.interrupt();
  //  workQueue.clear();
//    try {
//      workQueue.offer(new Runnable() {
//        @Override
//        public void run() {
//          // noop to wake from take
//        }
//      });
//      workQueue.offer(new Runnable() {
//        @Override
//        public void run() {
//          // noop to wake from take
//        }
//      });
//      workQueue.offer(new Runnable() {
//        @Override
//        public void run() {
//          // noop to wake from take
//        }
//      });


   //   workerFuture.cancel(true);
//    } catch (NullPointerException e) {
//      // okay
//    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown();
    return Collections.emptyList();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return !available.hasQueuedThreads() && shutdown;
  }

  @Override
  public boolean awaitTermination(long l, TimeUnit timeUnit)
      throws InterruptedException {
    assert ObjectReleaseTracker.release(this);
    TimeOut timeout = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    while (available.hasQueuedThreads() || workQueue.peek() != null) {
      if (timeout.hasTimedOut()) {
        throw new RuntimeException("Timeout");
      }

     //zaa System.out.println("WAIT : " + workQueue.size() + " " + available.getQueueLength() + " " + workQueue.toString());
      Thread.sleep(10);
    }
//    workQueue.clear();

//    workerFuture.cancel(true);
    terminated = true;
    worker.interrupt();
    worker.join();

   // worker.interrupt();
    return true;
  }


  @Override
  public void execute(Runnable runnable) {

//    if (shutdown) {
//      runnable.run();
//      return;
//    }

    if (runnable instanceof ParWork.SolrFutureTask) {
      ParWork.getEXEC().execute(runnable);
      return;
    }

    boolean success = this.workQueue.offer(runnable);
    if (!success) {
     // log.warn("No room in the queue, running in caller thread {} {} {} {}", workQueue.size(), isShutdown(), isTerminated(), worker.isAlive());
      runnable.run();
    } else {
      if (worker == null) {
        synchronized (this) {
          if (worker == null) {
            worker = new Worker();
            worker.setDaemon(true);
            worker.start();
          }
        }
      }
    }
  }


  public Integer getMaximumPoolSize() {
    return maxSize;
  }

  public boolean checkLoad() {
    double load = ManagementFactory.getOperatingSystemMXBean()
        .getSystemLoadAverage();
    if (load < 0) {
      log.warn("SystemLoadAverage not supported on this JVM");
    }

    double ourLoad = ParWork.getSysStats().getAvarageUsagePerCPU();

    if (ourLoad > 99.0D) {
      return false;
    } else {
      double sLoad = load / (double) ParWork.PROC_COUNT;
      if (sLoad > ParWork.PROC_COUNT) {
        return false;
      }
      if (log.isDebugEnabled()) log.debug("ParWork, load:" + sLoad);

    }
    return true;
  }
}
