/**
 * Copyright (C) 2009 University of Twente. All rights reserved.
 * Use is subject to license terms -- see license.txt.
 */

package eu.semaine.components.dialogue.interpreters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jms.JMSException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import eu.semaine.components.Component;
import eu.semaine.components.dialogue.actionproposers.UtteranceActionProposer;
import eu.semaine.components.dialogue.datastructures.EmotionEvent;
import eu.semaine.components.dialogue.test.DMLogger;
import eu.semaine.datatypes.stateinfo.AgentStateInfo;
import eu.semaine.datatypes.stateinfo.DialogStateInfo;
import eu.semaine.datatypes.stateinfo.StateInfo;
import eu.semaine.datatypes.stateinfo.UserStateInfo;
import eu.semaine.datatypes.xml.SemaineML;
import eu.semaine.exceptions.MessageFormatException;
import eu.semaine.jms.message.SEMAINEMessage;
import eu.semaine.jms.message.SEMAINEStateMessage;
import eu.semaine.jms.message.SEMAINEXMLMessage;
import eu.semaine.jms.receiver.StateReceiver;
import eu.semaine.jms.receiver.XMLReceiver;
import eu.semaine.jms.sender.StateSender;
import eu.semaine.util.XMLTool;

/**
 * The TurnTakingInterpreter looks at the behaviour of the user and has to decide when is
 * a good moment to start speaking. When this moment is decided the decision is send forward.
 * 
 * Input
 * UserStateReceiver('semaine.data.state.user')		--> user speaking state and detected emotions
 * AgentStateReceiver('semaine.data.state.agent')	--> current character
 * 
 * Output
 * AgentStateSender('semaine.data.state.agent')		--> take/release turn messages
 * 
 * @author MaatM
 *
 */

public class TurnTakingInterpreter extends Component
{
	/* Possible turn states */
	private final static int WAITING = 0;
	private final static int SILENT = 1;
	private final static int SPEAKING = 2;
	
	private HashMap<String,Integer> charNumbers = new HashMap<String,Integer>();
	
	/* Take/release turn threshold */
	private final static int POPPY_TT_THRESHOLD = 60;
	private final static int PRUDENCE_TT_THRESHOLD = 80;
	private final static int SPIKE_TT_THRESHOLD = 30;
	private final static int OBADIAH_TT_THRESHOLD = 100;
	private int curr_TT_Threshold = 100;
	
	private String convState = "";

	/* Senders and Receivers */
	private StateReceiver userStateReceiver;
	private StateReceiver agentStateReceiver;
	private StateReceiver dialogStateReceiver;
	private XMLReceiver contextReceiver;
	private StateSender agentStateSender;

	/* Turn state of speaker */
	private int userSpeakingState = 0; // 	0 = unknown, 1 = silence, 2 = speaking
	private long userSpeakingStateTime = 0;
	private long latestUtteranceLength = 0;
	
	/* Turn state of Agent */
	private int agentSpeakingState = 1; //	1 = silence, 2 = speaking
	private long agentSpeakingStateTime = 0;
	private int agentSpeakingIntention = 0; //	1 = silence, 2 = speaking
	private long agentSpeakingIntentionTime = 0;
	
	/*  The current character*/
	private static final String USER = "user";
	private int character;
	
	/* List of detected emotion events (generated by the EmotionInterpreter) */
	private ArrayList<EmotionEvent> detectedEmotions = new ArrayList<EmotionEvent>();
	private int recentEmotionCounter = 0;
	private int index = 0;
	
	/* If a backchannel was given this silence */
	private boolean backchannel_given = false;
	
	/* Most recent detected words */
	private String currentDetectedKeywords = "";

	/**
	 * Constructor of TurnTakingInterpreter
	 * Initializes the senders and receivers, and sets the waitingtime for Act()	
	 * @throws JMSException
	 */
	public TurnTakingInterpreter() throws JMSException
	{
		super("TurnTakingInterpreter");
		
		charNumbers.put("poppy", UtteranceActionProposer.POPPY);
		charNumbers.put("prudence", UtteranceActionProposer.PRUDENCE);
		charNumbers.put("spike", UtteranceActionProposer.SPIKE);
		charNumbers.put("obadiah", UtteranceActionProposer.OBADIAH);
		
		waitingTime = 50;

		userStateReceiver = new StateReceiver( "semaine.data.state.user.behaviour", StateInfo.Type.UserState);
		receivers.add( userStateReceiver );
		agentStateReceiver = new StateReceiver( "semaine.data.state.agent", StateInfo.Type.AgentState);
		receivers.add( agentStateReceiver );
		dialogStateReceiver = new StateReceiver( "semaine.data.state.dialog", StateInfo.Type.DialogState);
		receivers.add( dialogStateReceiver );
		contextReceiver = new XMLReceiver("semaine.data.state.context");
		receivers.add( contextReceiver );
		
		agentStateSender = new StateSender( "semaine.data.state.agent", StateInfo.Type.AgentState, getName() );
		senders.add( agentStateSender );
	}

	
	/**
	 * Reads the message, filters out the detected user speaking state and the detected emotions, 
	 * and tries to determine if the agent should start speaking.
	 */
	public void react( SEMAINEMessage m ) throws JMSException
	{
		if (m instanceof SEMAINEStateMessage) {
			SEMAINEStateMessage sm = (SEMAINEStateMessage) m;
			StateInfo stateInfo = sm.getState();
			StateInfo.Type stateInfoType = stateInfo.getType();
			switch (stateInfoType) {
			case UserState:
				/* Update current detected words */
				if( stateInfo.hasInfo("userUtterance") ) {
					currentDetectedKeywords = stateInfo.getInfo("userUtterance");
				}

				/* Updates user speaking state (speaking or silent) */
				setUserSpeakingState(stateInfo);
				
				/* Updates detected emotions (valence, arousal, interest) */
				addDetectedEmotions(stateInfo);
				
				/* called to determine the turn state of the agent */
				determineAgentTurn();
				break;

			case DialogState:
				/* Processes Dialog state updates */
				/* updates agent speaking state */
				setAgentSpeakingState(stateInfo);
				
				if( stateInfo.hasInfo("convState") ) {
					convState = stateInfo.getInfo("convState");
				}
				
				break;
				
			case AgentState:
				/* Processes Agent state changes */
				/* processes an agent backchannel */
				processBackchannel(stateInfo);
				break;
				
			case ContextState:
				updateCharacter( stateInfo );
				break;
				
		    default:
		    	// We could complain here if we were certain we don't expect other state infos, as in:
		    	// throw new MessageFormatException("Unexpected state info type: "+stateInfo.getType().toString());
			}
		}
	}
	
	public void updateCharacter( StateInfo stateInfo ) throws JMSException
	{
		if( stateInfo.hasInfo("character") ) {
			character = charNumbers.get( stateInfo.getInfo("character") );
		}
	}
	
	/**
	 * Called every 50ms, reevaluates the current situation and tries to determine if the agent
	 * should start talking. 
	 */
	public void act() throws JMSException
	{
		determineAgentTurn();
	}
	
	/**
	 * Reads the received Message and tries to filter out the detected user speaking state.
	 * @param m - the received message
	 */
	public void setAgentSpeakingState(StateInfo dialogInfo)
	{
		if( dialogInfo.hasInfo("agentTurnState") ) {
			if( dialogInfo.getInfo("agentTurnState").equals("true") ) {
				backchannel_given = false;
				agentSpeakingState = SPEAKING;
				agentSpeakingStateTime = meta.getTime();
				detectedEmotions.clear();
				recentEmotionCounter = 0;
			} else if( dialogInfo.getInfo("agentTurnState").equals("false") ) {
				backchannel_given = false;
				agentSpeakingState = SILENT;
				agentSpeakingStateTime = meta.getTime();
				if( userSpeakingState == SILENT ) {
					userSpeakingState = WAITING;
					userSpeakingStateTime = meta.getTime();
				} else if( userSpeakingState == WAITING ) {
					userSpeakingStateTime = meta.getTime();
				}
			}
		}
	}

	/**
	 * Reads the received Message and tries to filter out the detected user speaking state.
	 * @param m - the received message
	 */
	public void setUserSpeakingState(StateInfo userInfo)
	{
		if( userInfo.hasInfo("speaking") ) {
			if( userInfo.getInfo("speaking").equals("true") ) {
				if( userSpeakingState != SPEAKING ) {
					DMLogger.getLogger().log(meta.getTime(), "UserAction:UserStartedSpeaking" );
					System.out.println("Detected user speaking");
					backchannel_given = false;
					userSpeakingState = SPEAKING;
					userSpeakingStateTime = meta.getTime();
				}
			} else if( userInfo.getInfo("speaking").equals("false") ) {
				if( userSpeakingState != SILENT ) {
					DMLogger.getLogger().log(meta.getTime(), "UserAction:UserStoppedSpeaking words=" + currentDetectedKeywords );
					System.out.println("Detected user silent");
					latestUtteranceLength = meta.getTime() - userSpeakingStateTime;
					userSpeakingState = SILENT;
					userSpeakingStateTime = meta.getTime();
				}
			}
		}
	}
	
	/**
	 * Reads the received Message and tries to filter out a change of character
	 * @param am - the received message
	 */
	public void setCharacter(StateInfo agentInfo)
	{
		Map<String,String> agentInfoMap = agentInfo.getInfos();
		
		String newChar = agentInfoMap.get( "character" );
		if( newChar != null ) {
			if( newChar.equals("poppy") ) {
				character = UtteranceActionProposer.POPPY;
				curr_TT_Threshold = POPPY_TT_THRESHOLD;
			} else if( newChar.equals("prudence") ) {
				character = UtteranceActionProposer.PRUDENCE;
				curr_TT_Threshold = PRUDENCE_TT_THRESHOLD;
			} else if( newChar.equals("spike") ) {
				character = UtteranceActionProposer.SPIKE;
				curr_TT_Threshold = SPIKE_TT_THRESHOLD;
			} else if( newChar.equals("obadiah") ) {
				character = UtteranceActionProposer.OBADIAH;
				curr_TT_Threshold = OBADIAH_TT_THRESHOLD;
			}
		}
	}
	
	public void processBackchannel(StateInfo agentInfo)
	{
		// TODO: To Fix
		Map<String,String> agentInfoMap = agentInfo.getInfos();
		
		String intention = agentInfoMap.get( "turnTakingIntention" );
		if( intention != null && intention.equals("backchannel") ) {
			backchannel_given = true;
		}
	}
	
	/**
	 * Reads the received Message and tries to filter out the detected Emotion Events.
	 * @param m - the received message
	 */
	public void addDetectedEmotions(StateInfo userInfo)
	{
		if( userInfo.hasInfo("valence") ) {
			float valence = Float.parseFloat( userInfo.getInfo("valence") );
			EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.VALENCE, valence );
			if( detectedEmotions.size() == 0 || meta.getTime() - detectedEmotions.get(detectedEmotions.size()-1).getTime() > 500 ) {
				recentEmotionCounter++;
			}
			detectedEmotions.add( ee );
		}
		if( userInfo.hasInfo("arousal") ) {
			float arousal = Float.parseFloat( userInfo.getInfo("arousal") );
			EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.AROUSAL, arousal );
			if( detectedEmotions.size() == 0 || meta.getTime() - detectedEmotions.get(detectedEmotions.size()-1).getTime() > 500 ) {
				recentEmotionCounter++;
			}
			detectedEmotions.add( ee );
		}
		if( userInfo.hasInfo("interest") ) {
			float interest = Float.parseFloat( userInfo.getInfo("interest") );
			EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.INTEREST, interest );
			if( detectedEmotions.size() == 0 || meta.getTime() - detectedEmotions.get(detectedEmotions.size()-1).getTime() > 500 ) {
				recentEmotionCounter++;
			}
			detectedEmotions.add( ee );
		}
	}
	
	/**
	 * Determines if the agent should start speaking or not. It bases this decision
	 * on the speaking_intention, a value for how much the agent wants the turn.
	 */
	public void determineAgentTurn() throws JMSException
	{
		int speakingIntention = getSpeakingIntentionValue();
		if( speakingIntention >= curr_TT_Threshold && agentSpeakingIntention != SPEAKING && agentSpeakingState == SILENT ) {
			agentSpeakingIntention = SPEAKING;
			agentSpeakingIntentionTime = meta.getTime();
			sendAgentTurnState();
		} else if( speakingIntention < curr_TT_Threshold && agentSpeakingIntention != SILENT ) {
			agentSpeakingIntention = SILENT;
			agentSpeakingIntentionTime = meta.getTime();
			sendAgentTurnState();
		}
	}
	
	/**
	 * Calculates the speaking intention value for the current moment.
	 * This is a value for how much the agent wants the turn.
	 * This is calculated based on detected emotion events, the user speaking state, 
	 * the duration of this state, and the agent speaking state plus its duration.
	 * The higher the value, the higher the intention to speak.
	 * 
	 * Currently, the speaking intention value is the sum of these values: 
	 * user_silence_time_value: 	a value between 0 and 100 which is higher when the user is silent for a longer time (max is reached after 0.8 seconds).
	 * emotion_value: 				a value based on the number of detected emotion events. More detected events lead to a higher value.
	 * agent_silence_time_value: 	a value that's higher when the agent is silent for a longer period (max is reached after 30 seconds).
	 * agent_end_wait_value:		a value that starts at -100 when the agent finishes its utterance, and in the next 2 seconds slowly rises (linear) to 0;
	 * user_not_responding_value:	a value that rises from 0 to 100 when the user does not start talking after an agent-turn. It starts after 2 seconds, and then rises to 100 in 4 seconds unless the user starts speaking.
	 * @return
	 */
	public int getSpeakingIntentionValue()
	{
		int speakingIntention = 0;
		
		/* The components of the speaking intention value */
		int user_silence_time_value = 0;
		int emotion_value = 0;
		int agent_silence_time_value = 0;
		int agent_end_wait_value = 0;
		int user_not_responding_value = 0;
		
		/* silence_time_value */
		if( userSpeakingState == SILENT ) {
			double time = ((double)meta.getTime() - (double)userSpeakingStateTime)/1000;
			if( backchannel_given ) {
				time = time - 1;
			}
			//double value = Math.max( Math.min( -(Math.sqrt(time))+1, 100 ), 0 );
			double value;
			if( time >= 0 ) {
				if( convState.equals("listening") ) {
					time /= 9;
				}
				value = Math.max( Math.min( Math.pow(time+0.3,2), 1 ), 0 );
			} else {
				value = 0;
			}
			user_silence_time_value = (int)(value * 100);
		}
		
		/* emotion_value */
		emotion_value = Math.min( recentEmotionCounter*10, 80 );
		
		/* time_turn_value */
		if( agentSpeakingState == SILENT ) {
			double time = ((double)meta.getTime() - (double)agentSpeakingStateTime)/1000;
			double value = Math.min( (4/3)*time, 30 );
			agent_silence_time_value = (int)(value);
		}
		
		/* agent_end_wait_value */
		long agentSpeakingTime = meta.getTime() - agentSpeakingStateTime;
		long userSpeakingTime = meta.getTime() - userSpeakingStateTime;
		if( agentSpeakingState == SILENT && agentSpeakingTime < 1000 ) {
			agent_end_wait_value = ((int)(0.1*agentSpeakingTime - 100));
		}
		
		/* user_not_responding_value */
		//System.out.println("Agent:" + agentSpeakingState + ", User:" + userSpeakingState + ", userSpeakingTime: " + userSpeakingTime + ", agentSpeakingTime: " + agentSpeakingTime );
		if( agentSpeakingState == SILENT && userSpeakingState == WAITING && Math.abs(agentSpeakingTime - userSpeakingTime) < 30  ) {
			if( userSpeakingTime < 2000 ) {
				// Do nothing
			} else if( userSpeakingTime > 6000 ) {
				user_not_responding_value = 100;
			} else {
				user_not_responding_value = ((int)(0.025*(agentSpeakingTime-2000)));
			}
		}
		
		/* Calculating the speaking intention value */
		speakingIntention = user_silence_time_value + emotion_value + agent_silence_time_value + agent_end_wait_value + user_not_responding_value;
		//System.out.println( speakingIntention + "	= " + user_silence_time_value + "	+ " + emotion_value + "	+ " + agent_silence_time_value + "	+ " + agent_end_wait_value + "	+ " + user_not_responding_value + "			: currTime: " + meta.getTime() );
		DMLogger.getLogger().log(meta.getTime(), speakingIntention + "	= " + user_silence_time_value + "	+ " + emotion_value + "	+ " + agent_silence_time_value + "	+ " + agent_end_wait_value + "	+ " + user_not_responding_value);
		
		return speakingIntention;
	}
	
	public void sendAgentTurnState() throws JMSException
	{
		Map<String,String> agentStateInfo = new HashMap<String,String>();
		if( agentSpeakingIntention == SPEAKING ) {
			agentStateInfo.put("turnTakingIntention", "startSpeaking");
			DMLogger.getLogger().log(meta.getTime(), "TurnTakingIntention:StartSpeaking");
		} else if( agentSpeakingIntention == SILENT ) {
			agentStateInfo.put("turnTakingIntention", "stopSpeaking");
			DMLogger.getLogger().log(meta.getTime(), "TurnTakingIntention:StartSpeaking");
		} else {
			return;
		}
		
		AgentStateInfo asi = new AgentStateInfo( agentStateInfo );
		agentStateSender.sendStateInfo(asi, meta.getTime());	
	}
}
