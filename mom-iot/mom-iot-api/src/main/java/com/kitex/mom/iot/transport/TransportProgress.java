package com.kitex.mom.iot.transport;

import java.util.Objects;

/** Provider-neutral transport progress. Never establishes inventory or physical handover facts. */
public final class TransportProgress {
    private TransportProgress() {}

    public enum Phase {
        QUEUED,
        ACCEPTED,
        MOVING,
        ARRIVED_AWAITING_HANDOVER,
        FAILED,
        CANCELLED
    }

    public enum Action {
        ACCEPT,
        START,
        ARRIVE,
        FAIL,
        REQUEST_CANCEL,
        CONFIRM_CANCEL,
        MARK_UNKNOWN,
        CONFIRM_CURRENT
    }

    public record State(Phase confirmed, boolean unknown, boolean cancelRequested) {
        public State {
            Objects.requireNonNull(confirmed);
        }

        public String status() {
            return unknown
                    ? "UNKNOWN"
                    : cancelRequested && !terminal(confirmed) ? "CANCEL_PENDING" : confirmed.name();
        }
    }

    public static State initial() {
        return new State(Phase.QUEUED, false, false);
    }

    public static boolean terminal(Phase p) {
        return p == Phase.ARRIVED_AWAITING_HANDOVER || p == Phase.FAILED || p == Phase.CANCELLED;
    }

    public static State apply(State s, Action a) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(a);
        if (terminal(s.confirmed())) throw new IllegalArgumentException("运输阶段已结束，不能继续推进");
        return switch (a) {
            case ACCEPT -> {
                require(s.confirmed() == Phase.QUEUED);
                yield new State(Phase.ACCEPTED, false, s.cancelRequested());
            }
            case START -> {
                require(s.confirmed() == Phase.ACCEPTED);
                yield new State(Phase.MOVING, false, s.cancelRequested());
            }
            case ARRIVE -> {
                require(s.confirmed() == Phase.MOVING);
                yield new State(Phase.ARRIVED_AWAITING_HANDOVER, false, s.cancelRequested());
            }
            case FAIL -> new State(Phase.FAILED, false, s.cancelRequested());
            case REQUEST_CANCEL -> {
                require(!s.cancelRequested());
                yield new State(s.confirmed(), s.unknown(), true);
            }
            case CONFIRM_CANCEL -> {
                require(s.cancelRequested());
                yield new State(Phase.CANCELLED, false, true);
            }
            case MARK_UNKNOWN -> {
                require(!s.unknown());
                yield new State(s.confirmed(), true, s.cancelRequested());
            }
            case CONFIRM_CURRENT -> {
                require(s.unknown());
                yield new State(s.confirmed(), false, s.cancelRequested());
            }
        };
    }

    private static void require(boolean ok) {
        if (!ok) throw new IllegalArgumentException("运输事件与最后确认阶段不一致");
    }
}
