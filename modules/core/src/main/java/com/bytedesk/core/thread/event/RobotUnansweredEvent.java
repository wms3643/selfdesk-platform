package com.bytedesk.core.thread.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published when an AI response has no trusted knowledge-base evidence and the robot falls
 * back to its configured no-answer reply.
 */
public class RobotUnansweredEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String threadUid;
    private final String visitorMessageJson;
    private final String robotUid;
    private final String reason;

    public RobotUnansweredEvent(Object source, String threadUid, String visitorMessageJson,
            String robotUid, String reason) {
        super(source);
        this.threadUid = threadUid;
        this.visitorMessageJson = visitorMessageJson;
        this.robotUid = robotUid;
        this.reason = reason;
    }

    public String getThreadUid() {
        return threadUid;
    }

    public String getVisitorMessageJson() {
        return visitorMessageJson;
    }

    public String getRobotUid() {
        return robotUid;
    }

    public String getReason() {
        return reason;
    }
}
