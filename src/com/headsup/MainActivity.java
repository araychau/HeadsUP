package com.headsup;

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.TextView;
import android.widget.Toast;

import com.headsup.activation.OnActivatedListener;
import com.headsup.activation.OnInitListener;
import com.headsup.activation.VoiceActivation;
import com.headsup.recognition.CommandsListener;
import com.headsup.recognition.CommandsRecognition;
import com.headsup.statemachine.State;
import com.headsup.statemachine.StateMachine;

public class MainActivity extends Activity {

	private static final int SM_MESSAGE_COMMAND = 0;
	private static final int SM_MESSAGE_CALL = 1;
	private VoiceActivation mVoiceActivation;
	private TextView mTextView;
	private MyStateMachine mMyStateMachine;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mTextView = (TextView) findViewById(R.id.textView1);
		mTextView.setText("Starting");
		
		mMyStateMachine = new MyStateMachine();

		mVoiceActivation = new VoiceActivation(this, new OnInitListener() {
			@Override
			public void onSuccess() {
				mMyStateMachine.start();
			}

			@Override
			public void onError() {
				Toast.makeText(MainActivity.this, "Failed to start", Toast.LENGTH_LONG).show();
			}
		});

		mVoiceActivation.setOnActivatedListener(new OnActivatedListener() {
			@Override
			public void OnActivated() {
				mMyStateMachine.sendMessage(SM_MESSAGE_COMMAND);
			}
		});

		TelephonyManager TelephonyMgr = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		TelephonyMgr.listen(new MyPhoneStateListener(), PhoneStateListener.LISTEN_CALL_STATE);
	}

	private void addLog(final String string) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				mTextView.append("\n" + string);
			}
		});
	}

	private String searchResult(ArrayList<Result> results, String name) {
		for (int i = 0; i < results.size(); i++) {
			Result result = results.get(i);
			if (result.name.equals(name)) {
				return result.value;
			}
		}
		return null;
	}

	private ArrayList<Result> getApps() {
		ArrayList<Result> r = new ArrayList<Result>();
		List<PackageInfo> packages = getPackageManager().getInstalledPackages(0);
		for (int i = 0; i < packages.size(); i++) {
			PackageInfo packageInfo = packages.get(i);
			Result result = new Result();
			result.name = packageInfo.applicationInfo.loadLabel(getPackageManager()).toString();
			result.value = packageInfo.packageName;
			r.add(result);
		}
		return r;
	}

	private ArrayList<Result> getContacts() {
		ArrayList<Result> r = new ArrayList<Result>();
		ContentResolver contentResolver = getContentResolver();
		Cursor cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
		if (cursor.getCount() > 0) {
			while (cursor.moveToNext()) {
				if (Integer.parseInt(cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))) > 0) {
					String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
					Cursor pCur = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[] { id }, null);
					while (pCur.moveToNext()) {
						Result result = new Result();
						result.name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
						result.value = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
						r.add(result);
						break;
					}
					pCur.close();
				}
			}
		}
		cursor.close();
		return r;
	}

	private class Result {
		public String name, value;
	}

	class MyStateMachine extends StateMachine {

		// private StateMachineEnterExitTransitionToTest mThisSm;
		private StateActivation mStateActivation = new StateActivation();
		private StateCommandWaiting mStateCommandWaiting = new StateCommandWaiting();
		private StateCommandHandling mStateCommandHandling = new StateCommandHandling();
		private StateCalling mStateCalling = new StateCalling();
		private StateSendMessage mStateSendMessage = new StateSendMessage();

		MyStateMachine() {
			super();
			// mThisSm = this;
			// setDbg(true);

			addState(mStateActivation);
			addState(mStateCommandWaiting);
			addState(mStateCommandHandling);
			addState(mStateCalling);
			addState(mStateSendMessage);

			setInitialState(mStateActivation);
		}

		class StateActivation extends State {
			@Override
			public void enter() {
				mVoiceActivation.listen();
				addLog("Waiting for \"Ok HeadsUP\"");
			}

			@Override
			public void processMessage(int what) {
				if (what == SM_MESSAGE_COMMAND) transitionTo(mStateCommandWaiting);
			}
		}

		class StateCommandWaiting extends State {
			@Override
			public void enter() {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						CommandsRecognition commandsRecognition = new CommandsRecognition(MainActivity.this, true);
						commandsRecognition.setCommandsListener(new CommandsListener() {
							@Override
							public void onResult(ArrayList<String> result) {
								addLog("Google caught: " + result.toString());
								mStateCommandHandling.setResults(result);
								transitionTo(mStateCommandHandling);
							}

							@Override
							public void onError(int errorCode, String error) {
								addLog("Error: " + error);
								transitionTo(mStateActivation);
							}
						});
						commandsRecognition.startListening();
					}
				});
				addLog("Listening your command");
			}
		}

		class StateCommandHandling extends State {
			private ArrayList<String> mCommands;

			public void setResults(ArrayList<String> commands) {
				mCommands = commands;
			}

			@Override
			public void enter() {
				addLog("Executing the command");
				boolean handled = false;
				for (int i = 0; i < mCommands.size(); i++) {
					String command = mCommands.get(i);
					if (command.startsWith("call")) {
						if (command.length() > 5) {
							String number = searchResult(getContacts(), command.replaceAll("^call\\s+", ""));
							if (number != null) {
								handled = true;
								mStateCalling.setNumber(number);
								transitionTo(mStateCalling);
								break;
							}
						}
					} else if (command.startsWith("start app") || command.startsWith("open app")) {
						if (command.length() > 9) {
							String app = searchResult(getApps(), command.replaceAll("^(start|open) app\\s+", ""));
							if (app != null) {
								handled = true;
								Intent intent = new Intent();
								PackageManager manager = getPackageManager();
								intent = manager.getLaunchIntentForPackage(app);
								intent.addCategory(Intent.CATEGORY_LAUNCHER);
								startActivity(intent);
								transitionTo(mStateActivation);
								break;
							}
						}
					} else if (command.startsWith("send message") || command.startsWith("send a text")) {
						if (command.length() > 11) {
							String name = command.replaceAll("^((send message)|(send a text))\\s+(to\\s+)?", "");
							String number = searchResult(getContacts(), name);
							if (number != null) {
								handled = true;
								mStateSendMessage.setNumber(number);
								transitionTo(mStateSendMessage);
								break;
							}
						}
					}
				}
				if (!handled) {
					addLog("Unknown command");
					transitionTo(mStateActivation);
				}
			}
		}

		class StateCalling extends State {
			private String mNumber;

			public void setNumber(String number) {
				mNumber = number;
			}

			@Override
			public void enter() {
				addLog("Calling " + mNumber);
				Intent intent = new Intent(Intent.ACTION_CALL);
				intent.setData(Uri.parse("tel:" + mNumber));
				startActivity(intent);
			}

			@Override
			public void processMessage(int what) {
				addLog("Call ended");
				if (what == SM_MESSAGE_CALL) transitionTo(mStateActivation);
			}
		}

		class StateSendMessage extends State {
			private String mNumber;

			public void setNumber(String number) {
				mNumber = number;
			}

			@Override
			public void enter() {
				addLog("Listen message text");
				CommandsRecognition commandsRecognition = new CommandsRecognition(MainActivity.this, false);
				commandsRecognition.setCommandsListener(new CommandsListener() {
					@Override
					public void onResult(ArrayList<String> result) {
						addLog("Message text: " + result.toString());
						transitionTo(mStateActivation);
					}

					@Override
					public void onError(int errorCode, String error) {
						addLog("Error: " + error);
						transitionTo(mStateActivation);
					}
				});
				commandsRecognition.startListening();
			}
		}

		// @Override
		// protected void onHalting() {
		// synchronized (mThisSm) {
		// mThisSm.notifyAll();
		// }
		// }

	}

	class MyPhoneStateListener extends PhoneStateListener {
		public void onCallStateChanged(int state, String incomingNumber) {
			super.onCallStateChanged(state, incomingNumber);
			switch (state) {
			case TelephonyManager.CALL_STATE_IDLE:
				mMyStateMachine.sendMessage(SM_MESSAGE_CALL);
				break;
			// case TelephonyManager.CALL_STATE_OFFHOOK:
			// break;
			// case TelephonyManager.CALL_STATE_RINGING:
			// break;
			}
		}
	}

}