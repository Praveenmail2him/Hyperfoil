package io.sailrocket.core.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import io.sailrocket.api.BenchmarkDefinitionException;
import io.sailrocket.api.ConcurrentPool;
import io.sailrocket.api.HttpClientPool;
import io.sailrocket.api.Phase;
import io.sailrocket.core.api.PhaseInstance;
import io.sailrocket.api.Session;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public abstract class PhaseInstanceImpl<D extends Phase> implements PhaseInstance {
   protected static final Logger log = LoggerFactory.getLogger(PhaseInstanceImpl.class);
   protected static final boolean trace = log.isTraceEnabled();

   private static Map<Class<? extends Phase>, Function<? extends Phase, PhaseInstance>> constructors = new HashMap<>();

   protected D def;
   protected ConcurrentPool<Session> sessions;
   protected Lock statusLock;
   protected Condition statusCondition;
   // Reads are done without locks
   protected volatile Status status = Status.NOT_STARTED;
   protected long absoluteStartTime;
   protected AtomicInteger activeSessions = new AtomicInteger(0);
   private Throwable error;

   public static PhaseInstance newInstance(Phase def) {
      Function<Phase, PhaseInstance> ctor = (Function<Phase, PhaseInstance>) constructors.get(def.getClass());
      if (ctor == null) throw new BenchmarkDefinitionException("Unknown phase type: " + def);
      return ctor.apply(def);
   }

   static {
      constructors.put(Phase.AtOnce.class, (Function<Phase.AtOnce, PhaseInstance>) AtOnce::new);
      constructors.put(Phase.Always.class, (Function<Phase.Always, PhaseInstance>) Always::new);
      constructors.put(Phase.RampPerSec.class, (Function<Phase.RampPerSec, PhaseInstance>) RampPerSec::new);
      constructors.put(Phase.ConstantPerSec.class, (Function<Phase.ConstantPerSec, PhaseInstance>) ConstantPerSec::new);
      constructors.put(Phase.Sequentially.class, (Function<Phase.Sequentially, PhaseInstance>) Sequentially::new);
   }

   protected PhaseInstanceImpl(D def) {
      this.def = def;
   }

   @Override
   public D definition() {
      return def;
   }

   @Override
   public Status status() {
      return status;
   }

   @Override
   public long absoluteStartTime() {
      return absoluteStartTime;
   }

   @Override
   public void start(HttpClientPool clientPool) {
      assert status == Status.NOT_STARTED;
      status = Status.RUNNING;
      absoluteStartTime = System.currentTimeMillis();
      log.debug("{} changing status to RUNNING", def.name);
      proceed(clientPool);
   }

   @Override
   public void finish() {
      assert status == Status.RUNNING;
      status = Status.FINISHED;
      log.debug("{} changing status to FINISHED", def.name);
      if (activeSessions.compareAndSet(0, Integer.MIN_VALUE)) {
         // It is possible that we will activate another session after setting status to TERMINATED but such session
         // should check the status again as its first action and terminate
         status = Status.TERMINATED;
         log.debug("{} changing status to TERMINATED", def.name);
      }
   }

   @Override
   public void terminate() {
      status = Status.TERMINATING;
      log.debug("{} changing status to TERMINATING", def.name);
      if (activeSessions.compareAndSet(0, Integer.MIN_VALUE)) {
         status = Status.TERMINATED;
         log.debug("{} changing status to TERMINATED", def.name);
      }
   }

   // TODO better name
   @Override
   public void setComponents(ConcurrentPool<Session> sessions, Lock statusLock, Condition statusCondition) {
      this.sessions = sessions;
      this.statusLock = statusLock;
      this.statusCondition = statusCondition;
   }

   @Override
   public void notifyFinished(Session session) {
      int numActive = activeSessions.decrementAndGet();
      log.trace("{} has {} active sessions", def.name, numActive);
      if (numActive == 0) {
         setTerminated();
      }
   }

   @Override
   public void notifyTerminated(Session session) {
      int numActive = activeSessions.decrementAndGet();
      log.trace("{} has {} active sessions", def.name, numActive);
      if (numActive == 0) {
         setTerminated();
      }
   }

   @Override
   public void setTerminated() {
      statusLock.lock();
      try {
         if (status.isFinished()) {
            status = Status.TERMINATED;
            log.debug("{} changing status to TERMINATED", def.name);
            statusCondition.signal();
         }
      } finally {
         statusLock.unlock();
      }
   }

   @Override
   public void fail(Throwable error) {
      this.error = error;
      terminate();
   }

   @Override
   public Throwable getError() {
      return error;
   }

   public static class AtOnce extends PhaseInstanceImpl<Phase.AtOnce> {
      public AtOnce(Phase.AtOnce def) {
         super(def);
      }

      @Override
      public void proceed(HttpClientPool clientPool) {
         assert activeSessions.get() == 0;
         activeSessions.set(def.users);
         for (int i = 0; i < def.users; ++i) {
            sessions.acquire().proceed();
         }
      }

      @Override
      public void reserveSessions() {
         sessions.reserve(def.users);
      }
   }

   public static class Always extends PhaseInstanceImpl<Phase.Always> {
      public Always(Phase.Always def) {
         super(def);
      }

      @Override
      public void proceed(HttpClientPool clientPool) {
         assert activeSessions.get() == 0;
         activeSessions.set(def.users);
         for (int i = 0; i < def.users; ++i) {
            sessions.acquire().proceed();
         }
      }

      @Override
      public void reserveSessions() {
         sessions.reserve(def.users);
      }

      @Override
      public void notifyFinished(Session session) {
         if (status.isFinished()) {
            super.notifyFinished(session);
         } else {
            session.reset();
            session.proceed();
         }
      }
   }

   public static class RampPerSec extends PhaseInstanceImpl<Phase.RampPerSec> {
      private int startedUsers = 0;

      public RampPerSec(Phase.RampPerSec def) {
         super(def);
      }

      @Override
      public void proceed(HttpClientPool clientPool) {
         if (status.isFinished()) {
            return;
         }
         long now = System.currentTimeMillis();
         long delta = now - absoluteStartTime;
         int required = (int) (delta * def.initialUsersPerSec + (def.targetUsersPerSec - def.initialUsersPerSec) * delta / def.duration) / 1000;
         for (int i = required - startedUsers; i > 0; --i) {
            int numActive = activeSessions.incrementAndGet();
            if (numActive < 0) {
               // finished
               return;
            }
            if (trace) {
               log.trace("{} has {} active sessions", def.name, numActive);
            }
            Session session = sessions.acquire();
            session.proceed();
         }
         startedUsers = Math.max(startedUsers, required);
         long denominator = def.targetUsersPerSec + def.initialUsersPerSec * (def.duration - 1);
         // rounding up, not down as default integer division
         long nextDelta = (1000 * (startedUsers + 1) * def.duration + denominator - 1)/ denominator;
         if (trace) {
            log.trace("{}: {} after start, {} started, next user in {} ms", def.name, delta, startedUsers, nextDelta - delta);
         }
         clientPool.schedule(() -> proceed(clientPool), nextDelta - delta, TimeUnit.MILLISECONDS);
      }

      @Override
      public void reserveSessions() {
         sessions.reserve(def.maxSessionsEstimate);
      }

      @Override
      public void notifyFinished(Session session) {
         session.reset();
         sessions.release(session);
         super.notifyFinished(session);
      }
   }

   public static class ConstantPerSec extends PhaseInstanceImpl<Phase.ConstantPerSec> {
      private int startedUsers = 0;

      public ConstantPerSec(Phase.ConstantPerSec def) {
         super(def);
      }

      @Override
      public void proceed(HttpClientPool clientPool) {
         if (status.isFinished()) {
            return;
         }
         long now = System.currentTimeMillis();
         long delta = now - absoluteStartTime;
         int required = (int) (delta * def.usersPerSec / 1000);
         for (int i = required - startedUsers; i > 0; --i) {
            int numActive = activeSessions.incrementAndGet();
            if (numActive < 0) {
               // finished
               return;
            }
            if (trace) {
               log.trace("{} has {} active sessions", def.name, numActive);
            }
            Session session = sessions.acquire();
            session.proceed();
         }
         startedUsers = Math.max(startedUsers, required);
         // mathematically, the formula below should be 1000 * (startedUsers + 1) / usersPerSec but while
         // integer division is rounding down, we're trying to round up
         long nextDelta = (1000 * (startedUsers + 1) + def.usersPerSec - 1)/ def.usersPerSec;
         if (trace) {
            log.trace("{}: {} after start, {} started, next user in {} ms", def.name, delta, startedUsers, nextDelta - delta);
         }
         clientPool.schedule(() -> proceed(clientPool), nextDelta - delta, TimeUnit.MILLISECONDS);
      }

      @Override
      public void reserveSessions() {
         sessions.reserve(def.maxSessionsEstimate);
      }

      @Override
      public void notifyFinished(Session session) {
         session.reset();
         sessions.release(session);
         super.notifyFinished(session);
      }
   }

   public static class Sequentially extends PhaseInstanceImpl<Phase.Sequentially> {
      private int counter = 0;

      public Sequentially(Phase.Sequentially def) {
         super(def);
      }

      @Override
      public void proceed(HttpClientPool clientPool) {
         assert activeSessions.get() == 0;
         int numActive = activeSessions.incrementAndGet();
         if (trace) {
            log.trace("{} has {} active sessions", def.name, numActive);
         }
         sessions.acquire().proceed();
      }

      @Override
      public void reserveSessions() {
         sessions.reserve(1);
      }

      @Override
      public void notifyFinished(Session session) {
         if (++counter >= def.repeats) {
            status = Status.TERMINATING;
            log.debug("{} changing status to TERMINATING", def.name);
            super.notifyFinished(session);
         } else {
            session.reset();
            session.proceed();
         }
      }
   }
}
