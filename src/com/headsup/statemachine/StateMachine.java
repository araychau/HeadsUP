package com.headsup.statemachine;

public class StateMachine {
	private State mCurrentState;
	//private ArrayList<State> mStates;
	private State mInitialState;

	public StateMachine() {
		//mStates = new ArrayList<State>();
	}

	protected final void addState(State state) {
		//mStates.add(state);
	}

	protected final void setInitialState(State initialState) {
		mInitialState = initialState;
	}

	public void start() {
		mCurrentState = mInitialState;
		mCurrentState.enter();
	}

	protected final void transitionTo(State destState) {
		if (mCurrentState != null) {
			mCurrentState.exit();
			mCurrentState = destState;
			mCurrentState.enter();
		}
	}

	public final void sendMessage(int what) {
		if (mCurrentState != null) mCurrentState.processMessage(what);
	}
}