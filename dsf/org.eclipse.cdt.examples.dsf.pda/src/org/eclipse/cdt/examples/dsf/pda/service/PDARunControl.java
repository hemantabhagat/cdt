/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson	AB		  - Modified for handling of multiple threads
 *******************************************************************************/
package org.eclipse.cdt.examples.dsf.pda.service;

import java.util.Arrays;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.cdt.dsf.concurrent.Immutable;
import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.datamodel.AbstractDMEvent;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.datamodel.IDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.debug.service.command.IEventListener;
import org.eclipse.cdt.dsf.service.AbstractDsfService;
import org.eclipse.cdt.dsf.service.DsfServiceEventHandler;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.examples.dsf.pda.PDAPlugin;
import org.eclipse.cdt.examples.dsf.pda.service.commands.AbstractPDACommand;
import org.eclipse.cdt.examples.dsf.pda.service.commands.PDACommandResult;
import org.eclipse.cdt.examples.dsf.pda.service.commands.PDAResumeCommand;
import org.eclipse.cdt.examples.dsf.pda.service.commands.PDAStepCommand;
import org.eclipse.cdt.examples.dsf.pda.service.commands.PDAStepReturnCommand;
import org.eclipse.cdt.examples.dsf.pda.service.commands.PDAVMResumeCommand;
import org.eclipse.cdt.examples.dsf.pda.service.commands.PDAVMSuspendCommand;
import org.osgi.framework.BundleContext;


/**
 * Service for monitoring and controlling execution state of the DPA 
 * program.
 * <p>
 * This service depends on the {@link PDACommandControl} service.
 * It must be initialized before this service is initialized.
 * </p>
 */
public class PDARunControl extends AbstractDsfService 
    implements IRunControl, IEventListener
{
    // Implementation note about tracking execution state:
    // This class implements event handlers for the events that are generated by 
    // this service itself.  When the event is dispatched, these handlers will
    // be called first, before any of the clients.  These handlers update the 
    // service's internal state information to make them consistent with the 
    // events being issued.  Doing this in the handlers as opposed to when 
    // the events are generated, guarantees that the state of the service will
    // always be consistent with the events.
    // The purpose of this pattern is to allow clients that listen to service 
    // events and track service state, to be perfectly in sync with the service
    // state.

    static final private IExecutionDMContext[] EMPTY_TRIGGERING_CONTEXTS_ARRAY = new IExecutionDMContext[0];  

    @Immutable 
    private static class ThreadResumedEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements IResumedDMEvent
    {
        private final StateChangeReason fReason;

        ThreadResumedEvent(PDAThreadDMContext ctx, StateChangeReason reason) { 
            super(ctx);
            fReason = reason;
        }
        
        public StateChangeReason getReason() {
            return fReason;
        }
        
        @Override
        public String toString() {
            return "THREAD RESUMED: " + getDMContext() + " (" + fReason + ")"; 
        }
    }
    
    @Immutable 
    private static class VMResumedEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements IContainerResumedDMEvent
    {
        private final StateChangeReason fReason;
        
        VMResumedEvent(PDAVirtualMachineDMContext ctx, StateChangeReason reason) { 
            super(ctx);
            fReason = reason;
        }

        public StateChangeReason getReason() {
            return fReason;
        }

        public IExecutionDMContext[] getTriggeringContexts() {
            return EMPTY_TRIGGERING_CONTEXTS_ARRAY;
        }
        
        @Override
        public String toString() {
            return "VM RESUMED: (" + fReason + ")"; 
        }
    }
    
    @Immutable
    private static class ThreadSuspendedEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements ISuspendedDMEvent
    {
        private final StateChangeReason fReason;

        ThreadSuspendedEvent(PDAThreadDMContext ctx, StateChangeReason reason) { 
            super(ctx);
            fReason = reason;
        }
        
        public StateChangeReason getReason() {
            return fReason;
        }

        @Override
        public String toString() {
            return "THREAD SUSPENDED: " + getDMContext() + " (" + fReason + ")"; 
        }
    }
    
    @Immutable 
    private static class VMSuspendedEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements IContainerSuspendedDMEvent
    {
        private final StateChangeReason fReason;

        final private IExecutionDMContext[] fTriggeringThreads;  
        
        VMSuspendedEvent(PDAVirtualMachineDMContext ctx, PDAThreadDMContext threadCtx, StateChangeReason reason) { 
            super(ctx);
            fReason = reason;
            if (threadCtx != null) {
                fTriggeringThreads = new IExecutionDMContext[] { threadCtx };
            } else {
                fTriggeringThreads = EMPTY_TRIGGERING_CONTEXTS_ARRAY;
            }
        }
    
        public StateChangeReason getReason() {
            return fReason;
        }

        public IExecutionDMContext[] getTriggeringContexts() {
            return fTriggeringThreads;
        }


        @Override
        public String toString() {
            return "THREAD SUSPENDED: " + getDMContext() + 
                " (" + fReason + 
                ", trigger = " + Arrays.asList(fTriggeringThreads) +
                ")"; 
        }
    }

    @Immutable 
    private static class ExecutionDMData implements IExecutionDMData {
        private final StateChangeReason fReason;
        ExecutionDMData(StateChangeReason reason) {
            fReason = reason;
        }
        public StateChangeReason getStateChangeReason() { return fReason; }
    }
    
    private static class ThreadStartedEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements IStartedDMEvent 
    {
        ThreadStartedEvent(PDAThreadDMContext threadCtx) {
            super(threadCtx);
        }
    }
    
    private static class ThreadExitedEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements IExitedDMEvent 
    {
        ThreadExitedEvent(PDAThreadDMContext threadCtx) {
            super(threadCtx);
        }
    }
    
    // Services 
    private PDACommandControl fCommandControl;

    // Reference to the virtual machine data model context
    private PDAVirtualMachineDMContext fDMContext;

    // VM state flags
	private boolean fVMSuspended = true;
    private boolean fVMResumePending = false;
    private boolean fVMSuspendPending = false;
	private boolean fVMStepping = false;
	private StateChangeReason fVMStateChangeReason;
	
	// Threads' state data 
    private static class ThreadInfo {
        final PDAThreadDMContext fContext;
        boolean fSuspended = false;
        boolean fResumePending = false;
        boolean fSuspendPending = false;
        boolean fStepping = false;
        StateChangeReason fStateChangeReason = StateChangeReason.UNKNOWN;        

        ThreadInfo(PDAThreadDMContext context) {
            fContext = context;
        }
        
        @Override
        public String toString() {
            return fContext.toString() + " (" +
                (fSuspended ? "SUSPENDED, " : "SUSPENDED, ") +
                fStateChangeReason + 
                (fResumePending ? ", RESUME_PENDING, " : "") +
                (fSuspendPending ? ", SUSPEND_PENDING, " : "") +
                (fStepping ? ", SUSPEND_PENDING, " : "") +
                ")";
            		
        }
    }

	private Map<Integer,  ThreadInfo> fThreads = new LinkedHashMap<Integer,  ThreadInfo>();
	
	
    public PDARunControl(DsfSession session) {
        super(session);
    }
    
    @Override
    protected BundleContext getBundleContext() {
        return PDAPlugin.getBundleContext();
    }
    
    @Override
    public void initialize(final RequestMonitor rm) {
        super.initialize(
            new RequestMonitor(getExecutor(), rm) { 
                @Override
                protected void handleSuccess() {
                    doInitialize(rm);
                }});
    }

    private void doInitialize(final RequestMonitor rm) {
        // Cache a reference to the command control and the virtual machine context
        fCommandControl = getServicesTracker().getService(PDACommandControl.class);
        fDMContext = fCommandControl.getContext();

        // Create the main thread context.
        fThreads.put(
            1,
            new ThreadInfo(new PDAThreadDMContext(getSession().getId(), fDMContext, 1)));

        // Add the run control service as a listener to PDA events, to catch 
        // suspended/resumed/started/exited events from the command control.
        fCommandControl.addEventListener(this);
        
        // Add the run control service as a listener to service events as well, 
        // in order to process our own suspended/resumed/started/exited events.
        getSession().addServiceEventListener(this, null);
        
        // Register the service with OSGi
        register(new String[]{IRunControl.class.getName(), PDARunControl.class.getName()}, new Hashtable<String,String>());
        
        rm.done();
    }

    @Override
    public void shutdown(final RequestMonitor rm) {
        fCommandControl.removeEventListener(this);
        getSession().removeServiceEventListener(this);
        super.shutdown(rm);
    }
    
    @Deprecated
    @SuppressWarnings("unchecked")
    public void getModelData(IDMContext dmc, DataRequestMonitor<?> rm) {
        // The getModelData() is deprecated and clients are expected to switch
        // to getExecutionData() and other data retrieve methods directly.
        // However the UI cache still uses it for now.
        if (dmc instanceof IExecutionDMContext) {
            getExecutionData((IExecutionDMContext)dmc, (DataRequestMonitor<IExecutionDMData>)rm); 
        } else {
            PDAPlugin.failRequest(rm, INVALID_HANDLE, "Unknown DMC type");
        }
    }
    
    public void eventReceived(Object output) {
        if (!(output instanceof String)) return;
        String event = (String)output;
        
        int nameEnd = event.indexOf(' ');
        nameEnd = nameEnd == -1 ? event.length() : nameEnd;
        String eventName = event.substring(0, nameEnd);
        
        PDAThreadDMContext thread = null;
        StateChangeReason reason = StateChangeReason.UNKNOWN;
        if (event.length() > nameEnd + 1) {
            if ( Character.isDigit(event.charAt(nameEnd + 1)) ) {
                int threadIdEnd = event.indexOf(' ', nameEnd + 1);
                threadIdEnd = threadIdEnd == -1 ? event.length() : threadIdEnd;
                try {
                    int threadId = Integer.parseInt(event.substring(nameEnd + 1, threadIdEnd));
                    if (fThreads.containsKey(threadId)) {
                        thread = fThreads.get(threadId).fContext;
                    } else {
                        // In case where a suspended event follows directly a 
                        // started event, a thread may not be in the list of 
                        // known threads yet.  In this case create the
                        // thread context based on the ID.
                        thread = new PDAThreadDMContext(getSession().getId(), fDMContext, threadId);
                    }
                } catch (NumberFormatException e) {}
                if (threadIdEnd + 1 < event.length()) {
                    reason = parseStateChangeReason(event.substring(threadIdEnd + 1));
                }
            } else {
                reason = parseStateChangeReason(event.substring(nameEnd + 1));
            }
        }
        
        // Handle PDA debugger suspended/resumed events and issue the 
        // corresponding Data Model events.  Do not update the state
        // information until we start dispatching the service events.
        IDMEvent<?> dmEvent = null;
        if (eventName.equals("suspended") && thread != null) {
            dmEvent = new ThreadSuspendedEvent(thread, reason);
        } else if (eventName.equals("resumed") && thread != null) {
            dmEvent = new ThreadResumedEvent(thread, reason);
        } else if (event.startsWith("vmsuspended")) {
            dmEvent = new VMSuspendedEvent(fDMContext, thread, reason);
        } else if (event.startsWith("vmresumed")) {
            dmEvent = new VMResumedEvent(fDMContext, reason);
        } else if (event.startsWith("started") && thread != null) {
            dmEvent = new ThreadStartedEvent(thread);
        } else if (event.startsWith("exited") && thread != null) {
            dmEvent = new ThreadExitedEvent(thread);
        }
        
        if (dmEvent != null) {
            getSession().dispatchEvent(dmEvent, getProperties());
        }
    }
    
    private StateChangeReason parseStateChangeReason(String reasonString) {
        if (reasonString.startsWith("breakpoint") || reasonString.startsWith("watch")) {
            return StateChangeReason.BREAKPOINT;
        } else if (reasonString.equals("step") || reasonString.equals("drop")) {
            return StateChangeReason.STEP;
        } else if (reasonString.equals("client")) {
            return StateChangeReason.USER_REQUEST;
        } else if (reasonString.startsWith("event")) {
            return StateChangeReason.SIGNAL;
        } else {
            return StateChangeReason.UNKNOWN;
        } 

    }
    
    @DsfServiceEventHandler 
    public void eventDispatched(ThreadResumedEvent e) {
        ThreadInfo info = fThreads.get(((PDAThreadDMContext)e.getDMContext()).getID());
        if (info != null) {
            info.fSuspended = false;
            info.fResumePending = false;
            info.fStateChangeReason = e.getReason();
            info.fStepping = e.getReason().equals(StateChangeReason.STEP);
        }
    }    


    @DsfServiceEventHandler 
    public void eventDispatched(VMResumedEvent e) {
        fVMSuspended = false;
        fVMResumePending = false;
        fVMStateChangeReason = e.getReason();
        fVMStepping = e.getReason().equals(StateChangeReason.STEP);
        for (ThreadInfo info : fThreads.values()) {
            info.fSuspended = false;
            info.fStateChangeReason = StateChangeReason.CONTAINER;
            info.fStepping = false;
        }
    }    

    @DsfServiceEventHandler 
    public void eventDispatched(ThreadSuspendedEvent e) {
        ThreadInfo info = fThreads.get(((PDAThreadDMContext)e.getDMContext()).getID());
        if (info != null) {
            info.fSuspended = true;
            info.fSuspendPending = false;
            info.fStateChangeReason = e.getReason();
            info.fStepping = e.getReason().equals(StateChangeReason.STEP);
        }
    }
    

    @DsfServiceEventHandler 
    public void eventDispatched(VMSuspendedEvent e) {
        fVMStateChangeReason = e.getReason();
        fVMSuspendPending = false;
        fVMSuspended = true;
        fVMStepping = false;
        List<IExecutionDMContext> triggeringContexts = Arrays.asList(e.getTriggeringContexts());
        for (ThreadInfo info : fThreads.values()) {
            info.fSuspended = true;
            info.fStateChangeReason = triggeringContexts.contains(info.fContext) 
                ? StateChangeReason.STEP : StateChangeReason.CONTAINER;
            info.fStepping = false;
        }        
    }
    
    @DsfServiceEventHandler 
    public void eventDispatched(ThreadStartedEvent e) {
        PDAThreadDMContext threadCtx = (PDAThreadDMContext)e.getDMContext();
        fThreads.put(threadCtx.getID(), new ThreadInfo(threadCtx));
    }    
    
    @DsfServiceEventHandler 
    public void eventDispatched(ThreadExitedEvent e) {
        PDAThreadDMContext threadCtx = (PDAThreadDMContext)e.getDMContext();
        fThreads.remove(threadCtx.getID());
    }    
    
    public void canResume(IExecutionDMContext context, DataRequestMonitor<Boolean> rm) {
        rm.setData(doCanResume(context));
        rm.done();
    }
    
    private boolean doCanResume(IExecutionDMContext context) {
        if (context instanceof PDAThreadDMContext) {
            PDAThreadDMContext threadContext = (PDAThreadDMContext)context; 
            // Threads can be resumed only if the VM is not suspended.
            if (!fVMSuspended) { 
                ThreadInfo state = fThreads.get(threadContext.getID());
                if (state != null) {
                    return state.fSuspended && !state.fResumePending;
                }
            }
        } else {
            return fVMSuspended && !fVMResumePending;
        }
        return false;
    }
    
    private boolean doCanStep(IExecutionDMContext context, StepType stepType) {
        if (stepType == StepType.STEP_OVER || stepType == StepType.STEP_RETURN) {
            if (context instanceof PDAThreadDMContext) {
                PDAThreadDMContext threadContext = (PDAThreadDMContext)context; 
                // Only threads can be stepped.  But they can be stepped
                // while the VM is suspended or when just the thread is suspended.
                ThreadInfo state = fThreads.get(threadContext.getID());
                if (state != null) {
                    return state.fSuspended && !state.fResumePending;
                }
            }
        }
        return false;        
    }

    public void canSuspend(IExecutionDMContext context, DataRequestMonitor<Boolean> rm) {
        rm.setData(doCanSuspend(context));
        rm.done();
    }
    
    private boolean doCanSuspend(IExecutionDMContext context) {
        if (context instanceof PDAThreadDMContext) {
            PDAThreadDMContext threadContext = (PDAThreadDMContext)context; 
            // Threads can be resumed only if the VM is not suspended.
            if (!fVMSuspended) { 
                ThreadInfo state = fThreads.get(threadContext.getID());
                if (state != null) {
                    return !state.fSuspended && state.fSuspendPending;
                }
            }
        } else {
            return !fVMSuspended && !fVMSuspendPending;
        }
        return false;
    }

	public boolean isSuspended(IExecutionDMContext context) {
        if (context instanceof PDAThreadDMContext) {
            PDAThreadDMContext threadContext = (PDAThreadDMContext)context; 
            // Threads can be resumed only if the VM is not suspended.
            if (!fVMSuspended) { 
                ThreadInfo state = fThreads.get(threadContext.getID());
                if (state != null) {
                    return state.fSuspended;
                }
            }
        } 
		return fVMSuspended;
	}

	public boolean isStepping(IExecutionDMContext context) {
	    if (!isSuspended(context)) {
            if (context instanceof PDAThreadDMContext) {
                PDAThreadDMContext threadContext = (PDAThreadDMContext)context; 
                // Threads can be resumed only if the VM is not suspended.
                if (!fVMStepping) { 
                    ThreadInfo state = fThreads.get(threadContext.getID());
                    if (state != null) {
                        return state.fStepping;
                    }
                } 
            } 
            return fVMStepping;
	    }
	    return false;
    }

	public void resume(IExecutionDMContext context, final RequestMonitor rm) {
		assert context != null;

		if (doCanResume(context)) { 
            if (context instanceof PDAThreadDMContext) {
                final PDAThreadDMContext threadCtx = (PDAThreadDMContext)context;
                fThreads.get(threadCtx.getID()).fResumePending = true;
                fCommandControl.queueCommand(
                    new PDAResumeCommand(threadCtx),
                    new DataRequestMonitor<PDACommandResult>(getExecutor(), rm) { 
                        @Override
                        protected void handleFailure() {
                            ThreadInfo threadState = fThreads.get(threadCtx.getID());
                            if (threadState != null) {
                                threadState.fResumePending = false;
                            }
                            super.handleFailure();
                        }
                    }
                );                
            } else {
                fVMResumePending = true;
                fCommandControl.queueCommand(
                	new PDAVMResumeCommand(fDMContext),
                	new DataRequestMonitor<PDACommandResult>(getExecutor(), rm) { 
                        @Override
                        protected void handleFailure() {
                            fVMResumePending = false;
                            super.handleFailure();
                        }
                	}
                );
            }
        } else {
            PDAPlugin.failRequest(rm, INVALID_STATE, "Given context: " + context + ", is already running.");
        }
	}
	
	public void suspend(IExecutionDMContext context, final RequestMonitor rm){
		assert context != null;

		if (doCanSuspend(context)) {
            if (context instanceof PDAThreadDMContext) {
                final PDAThreadDMContext threadCtx = (PDAThreadDMContext)context;
                fThreads.get(threadCtx.getID()).fSuspendPending = true;
                fCommandControl.queueCommand(
                    new PDAVMSuspendCommand(fDMContext),
                    new DataRequestMonitor<PDACommandResult>(getExecutor(), rm) { 
                        @Override
                        protected void handleFailure() {
                            ThreadInfo threadState = fThreads.get(threadCtx.getID());
                            if (threadState != null) {
                                threadState.fSuspendPending = false;
                            }
                            super.handleFailure();
                        }
                    }
                );
            } else {
                fVMSuspendPending = true; 
                fCommandControl.queueCommand(
                    new PDAVMSuspendCommand(fDMContext),
                    new DataRequestMonitor<PDACommandResult>(getExecutor(), rm) { 
                        @Override
                        protected void handleFailure() {
                            fVMSuspendPending = false;
                            super.handleFailure();
                        }
                    }
                );
            }
        } else {
            PDAPlugin.failRequest(rm, IDsfStatusConstants.INVALID_STATE, "Given context: " + context + ", is already suspended."); 
        }
    }
    
    public void canStep(IExecutionDMContext context, StepType stepType, DataRequestMonitor<Boolean> rm) {
        rm.setData(doCanStep(context, stepType));
        rm.done();
    }
    
    public void step(IExecutionDMContext context, StepType stepType, final RequestMonitor rm) {
    	assert context != null;
    	
    	if (doCanStep(context, stepType)) {
    	    final PDAThreadDMContext threadCtx = (PDAThreadDMContext)context;
            final boolean vmWasSuspneded = fVMSuspended;
    	    
    	    if (vmWasSuspneded) {
                fVMResumePending = true;
    	    } else {
    	        fThreads.get(threadCtx.getID()).fResumePending = true;
    	    }

    	    AbstractPDACommand<PDACommandResult> stepCommand = 
    	        stepType == StepType.STEP_RETURN 
    	            ? new PDAStepReturnCommand(threadCtx)
    	            : new PDAStepCommand(threadCtx);
    	           
    	    
            fCommandControl.queueCommand(
                stepCommand, 
                new DataRequestMonitor<PDACommandResult>(getExecutor(), rm) {
                    @Override
                    protected void handleFailure() {
                        // If the step command failed, we no longer
                        // expect to receive a resumed event.
                        if (vmWasSuspneded) {
                            fVMResumePending = false;
                        } else {
                            ThreadInfo threadState = fThreads.get(threadCtx.getID());
                            if (threadState != null) {
                                threadState.fResumePending = false;
                            }
                        }
                    }
                });

    	} else {
            PDAPlugin.failRequest(rm, INVALID_STATE, "Cannot resume context"); 
            return;
        }
    }

    public void getExecutionContexts(final IContainerDMContext containerDmc, final DataRequestMonitor<IExecutionDMContext[]> rm) {
        IExecutionDMContext[] threads = new IExecutionDMContext[fThreads.size()];
        int i = 0;
        for (ThreadInfo info : fThreads.values()) {
            threads[i++] = info.fContext;
        }
        rm.setData(threads);
        rm.done();
    }
    
	public void getExecutionData(IExecutionDMContext dmc, DataRequestMonitor<IExecutionDMData> rm) {
	    if (dmc instanceof PDAThreadDMContext) {
	        ThreadInfo info = fThreads.get(((PDAThreadDMContext)dmc).getID());
	        if (info == null) {
                PDAPlugin.failRequest(rm, INVALID_HANDLE, "Unknown DMC type");
	            return;
	        } 
            rm.setData( new ExecutionDMData(info.fStateChangeReason) );
	    } else {
	        rm.setData( new ExecutionDMData(fVMStateChangeReason) );
	    }
        rm.done();
    }
}
