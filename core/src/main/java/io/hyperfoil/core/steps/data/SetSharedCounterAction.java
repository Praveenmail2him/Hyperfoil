package io.hyperfoil.core.steps.data;

import org.kohsuke.MetaInfServices;

import io.hyperfoil.api.config.Embed;
import io.hyperfoil.api.config.Name;
import io.hyperfoil.api.session.Action;
import io.hyperfoil.api.session.ResourceUtilizer;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.api.session.ThreadData;
import io.hyperfoil.core.builders.IntSourceBuilder;
import io.hyperfoil.function.SerializableToIntFunction;

public class SetSharedCounterAction implements Action, ResourceUtilizer {
   private final String key;
   private final SerializableToIntFunction<Session> input;

   public SetSharedCounterAction(String key, SerializableToIntFunction<Session> input) {
      this.key = key;
      this.input = input;
   }

   @Override
   public void run(Session session) {
      ThreadData.SharedCounter counter = session.threadData().getCounter(key);
      counter.set(input.applyAsInt(session));
   }

   @Override
   public void reserve(Session session) {
      session.threadData().reserveCounter(key);
   }

   /**
    * Sets value in a counter shared by all sessions in the same executor.
    */
   @MetaInfServices(Action.Builder.class)
   @Name("setSharedCounter")
   public static class Builder implements Action.Builder {
      private String key;
      @Embed
      public IntSourceBuilder<Builder> input = new IntSourceBuilder<>(this);

      /**
       * Identifier for the counter.
       *
       * @param key Name.
       * @return Self.
       */
      public Builder key(String key) {
         this.key = key;
         return this;
      }

      @Override
      public SetSharedCounterAction build() {
         return new SetSharedCounterAction(key, input.build());
      }
   }
}
