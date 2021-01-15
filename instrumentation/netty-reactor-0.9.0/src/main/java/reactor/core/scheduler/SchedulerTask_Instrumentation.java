package reactor.core.scheduler;

import com.newrelic.api.agent.Trace;
import com.newrelic.api.agent.weaver.Weave;
import com.newrelic.api.agent.weaver.Weaver;

@Weave(originalName = "reactor.core.scheduler.SchedulerTask")
final class SchedulerTask_Instrumentation {

    // We need to be able to link the Token here when executing on a supplied Scheduler via Mono::publishOn
    @Trace(async = true, excludeFromTransactionTrace = true)
    public Void call() {
        return Weaver.callOriginal();
    }
}
