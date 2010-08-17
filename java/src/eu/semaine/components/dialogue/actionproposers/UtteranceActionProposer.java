/**
 * Copyright (C) 2009 University of Twente. All rights reserved.
 * Use is subject to license terms -- see license.txt.
 */

package eu.semaine.components.dialogue.actionproposers;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.jms.JMSException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import eu.semaine.components.Component;
import eu.semaine.components.dialogue.behaviourselection.TemplateController;
import eu.semaine.components.dialogue.behaviourselection.behaviours.BehaviourClass;
import eu.semaine.components.dialogue.behaviourselection.template.TemplateState;
import eu.semaine.components.dialogue.datastructures.DMProperties;
import eu.semaine.components.dialogue.datastructures.DialogueAct;
import eu.semaine.components.dialogue.datastructures.EmotionEvent;
import eu.semaine.components.dialogue.datastructures.Response;
import eu.semaine.components.dialogue.informationstate.InformationState;
import eu.semaine.components.dialogue.test.DMLogger;
import eu.semaine.datatypes.stateinfo.ContextStateInfo;
import eu.semaine.datatypes.stateinfo.DialogStateInfo;
import eu.semaine.datatypes.stateinfo.StateInfo;
import eu.semaine.datatypes.xml.BML;
import eu.semaine.datatypes.xml.FML;
import eu.semaine.datatypes.xml.SSML;
import eu.semaine.datatypes.xml.SemaineML;
import eu.semaine.jms.IOBase.Event;
import eu.semaine.jms.message.SEMAINEFeatureMessage;
import eu.semaine.jms.message.SEMAINEMessage;
import eu.semaine.jms.message.SEMAINEStateMessage;
import eu.semaine.jms.message.SEMAINEXMLMessage;
import eu.semaine.jms.receiver.FeatureReceiver;
import eu.semaine.jms.receiver.StateReceiver;
import eu.semaine.jms.receiver.XMLReceiver;
import eu.semaine.jms.sender.FMLSender;
import eu.semaine.jms.sender.Sender;
import eu.semaine.jms.sender.StateSender;
import eu.semaine.util.XMLTool;

public class UtteranceActionProposer extends Component implements BehaviourClass
{
	/* Senders and Receivers */
	private StateReceiver agentStateReceiver;
	private StateReceiver userStateReceiver;
	private StateReceiver contextReceiver;
	private XMLReceiver callbackReceiver;
	private FeatureReceiver featureReceiver;
	private StateSender dialogStateSender;
	private StateSender contextSender;
	private FMLSender fmlSender;
	private FMLSender queuingFMLSender;
	private Sender commandSender;
	private XMLReceiver callbackReceiverAnimation;

	/* A local copy of the current Information State */
	private InformationState is;
	
	/* The TemplateController manages all Templates */
	private TemplateController templateController;
	
	/*A list with all detected emotion events of the current UserTurn */
	private ArrayList<EmotionEvent> detectedEmotions = new ArrayList<EmotionEvent>();
	
	/* A list with all data to send when the agent finishes speaking */
	private ArrayList<String[]> dataSendQueue = new ArrayList<String[]>();

	/* List of all detected Dialogue Acts (generated by the UtteranceInterpreter) */
	private ArrayList<DialogueAct> detectedDActs = new ArrayList<DialogueAct>();
	private int dActIndex = 0;
	
	/* All possible responses and response-groups, with their ID as the key */
	private HashMap<String,Response> responses;
	private HashMap<String,ArrayList<Response>> responseGroups;
	
	/* A list of responses that is prepared or being prepared, with the hashvalue of that response as key */
	private HashMap<String,String> preparedResponses = new HashMap<String,String>();
	private HashMap<String,String> preparingResponses = new HashMap<String,String>();
	
	/* A list of all detected audio-features of this UserTurn */
	private HashMap<String,ArrayList<Float>> detectedFeatures = new HashMap<String,ArrayList<Float>>();
	
	/* To generate random numbers */
	private Random random = new Random();
	
	/* Keeps track if the system has started or not */
	private boolean systemStarted = false;
	
	/* The latest AgentResponse that was executed */
	private Response latestResponse = null;
	private Response currResponse = null;
	private String currHash = null;
	
	/* If audiofeatures have to be stored at this moment or not */
	private boolean isStoringFeatures = false;
	
	/* A counter to keep track of the number of FML-objects that is send */
	private int output_counter = 1;
	
	/* The BehaviourClass-instance of this class */
	private static UtteranceActionProposer myClass;

	/**
	 * Constructor of ResponseActionProposer
	 * Initializes all Senders and Receivers, and initializes everything.
	 *
	 */
	public UtteranceActionProposer() throws JMSException
	{
		super("UtteranceActionProposer");

		/* Initialize receivers */
		agentStateReceiver = new StateReceiver( "semaine.data.state.agent", StateInfo.Type.AgentState );
		receivers.add( agentStateReceiver );
		userStateReceiver = new StateReceiver("semaine.data.state.user.behaviour", StateInfo.Type.UserState);
		receivers.add(userStateReceiver);
		contextReceiver = new StateReceiver("semaine.data.state.context", StateInfo.Type.ContextState);
		receivers.add( contextReceiver );
		callbackReceiver = new XMLReceiver("semaine.callback.output.audio");
		receivers.add(callbackReceiver);
		callbackReceiverAnimation = new XMLReceiver("semaine.callback.output.Animation");        
		receivers.add(callbackReceiverAnimation);
		featureReceiver = new FeatureReceiver("semaine.data.analysis.features.voice");
		receivers.add(featureReceiver);

		/* Initialize senders */
		dialogStateSender = new StateSender("semaine.data.state.dialog", StateInfo.Type.DialogState, getName());
		senders.add(dialogStateSender);
		contextSender = new StateSender("semaine.data.state.context", StateInfo.Type.ContextState, getName());
		senders.add(contextSender);
		fmlSender = new FMLSender("semaine.data.action.selected.function", getName());
		senders.add(fmlSender);
		queuingFMLSender = new FMLSender("semaine.data.action.prepare.function", getName());
		senders.add(queuingFMLSender);
		commandSender = new Sender("semaine.data.synthesis.lowlevel.command", "playCommand", getName());
		senders.add(commandSender);
		
		/* Create TemplateController */
		templateController = new TemplateController();
		for( String templateFile : DMProperties.getTemplateFiles() ) {
			System.out.println("Loading TemplateFile: " + templateFile);
			templateController.processTemplateFile(templateFile.trim());
		}
		templateController.addFunction(this);

		is = new InformationState();
		is.set("User.present", 0);
		
		/* Initialize Responses */
		String responseFile = DMProperties.getResponseFile();
		ResponseReader reader = new ResponseReader( responseFile );
		if( !reader.readData() ) {
			System.out.println( "Error, could not load Responses." );
		}
		responses = reader.getResponses();
		responseGroups = reader.getResponseGroups();
		
		/* Initializes detected AudioFeatures */
		detectedFeatures.put("F0", new ArrayList<Float>());
		detectedFeatures.put("pitchDirScore", new ArrayList<Float>());
		detectedFeatures.put("RMSEnergy", new ArrayList<Float>());
		detectedFeatures.put("LOGEnergy", new ArrayList<Float>());

		myClass = this;
		waitingTime = 50;
	}

	/**
	 * Returns the instance of this class.
	 */
	public static UtteranceActionProposer getMyClass()
	{
		return myClass;
	}

	/**
	 * Act method, called every 50ms. If the system is started and the Agent is not speaking at this moment,
	 * check all Templates and execute any fitting behaviour.
	 */
	public void act() throws JMSException
	{
		/* Summarize all detected AudioFeatures and put this in the InformationState */
		summarizeFeatures();
		
		String speakingState = is.getString("Agent.speakingState");
		if( systemStarted && (speakingState == null || (speakingState != null && speakingState.equals("listening"))) ) {
			is.set("currTime", (int)meta.getTime());
			String context = is.toString();
			TemplateState state = templateController.checkTemplates(is);
			if( state != null ) {
				DMLogger.getLogger().logUserTurn(meta.getTime(), latestResponse.getResponse() + "("+state.getTemplate().getId()+")", context);
				detectedEmotions.clear();
				dActIndex = detectedDActs.size();
				
				for( String featureName : detectedFeatures.keySet() ) {
					is.remove("UserTurn.AudioFeatures."+featureName+"_min");
					is.remove("UserTurn.AudioFeatures."+featureName+"_max");
					is.remove("UserTurn.AudioFeatures."+featureName+"_avg");
				}
				
				DMLogger.getLogger().log(meta.getTime(), "AgentAction:SendUtterance, type="+state.getTemplate().getId()+", utterance="+latestResponse.getResponse());
			}
		}
	}

	/**
	 * Updates the InformationState based on the SemaineMessages it receives
	 */
	public void react( SEMAINEMessage m ) throws JMSException
	{
		if( m instanceof SEMAINEFeatureMessage ) {
			/* Process AudioFeatures */
			if( isStoringFeatures ) {
				SEMAINEFeatureMessage fm = (SEMAINEFeatureMessage)m;
				
				/* Reads the feature names and values */
				String[] featureNames = fm.getFeatureNames();
				float[] featureValues = fm.getFeatureVector();
				
				for( int i=0; i<featureNames.length; i++ ) {
					if( detectedFeatures.get(featureNames[i]) != null ) {
						detectedFeatures.get(featureNames[i]).add(featureValues[i]);
					} else {
						ArrayList<Float> tempList = new ArrayList<Float>();
						tempList.add(featureValues[i]);
						detectedFeatures.put(featureNames[i],tempList);
					}
				}
			}
		} else if( m instanceof SEMAINEXMLMessage ){
			SEMAINEXMLMessage xm = ((SEMAINEXMLMessage)m);
			
			/* Process Agent-animation updates */
			if( !xm.getText().contains("fml_lip") ) {
				if( speechStarted(xm) ) {
					DMLogger.getLogger().log(meta.getTime(), "AgentAction:UtteranceStarted" );
					is.set("Agent.speakingState","speaking");
					is.set("Agent.speakingStateTime", (int)meta.getTime());
					sendData("agentTurnState", "true", "dialogstate");
					
					isStoringFeatures = false;
					for( ArrayList<Float> al : detectedFeatures.values() ) {
						al.clear();
					}
				}

				if( speechReady(xm) ) {
					DMLogger.getLogger().log(meta.getTime(), "AgentAction:UtteranceStopped" );
					is.set("Agent.speakingState","listening");
					is.set("Agent.speakingStateTime", (int)meta.getTime());
					is.set("User.speakingState","waiting");

					sendData("agentTurnState", "false", "dialogstate");
					for( String[] str : dataSendQueue ) {
						if( str.length == 3 ) {
							sendData(str[0], str[1], str[2]);
						}
					}
					dataSendQueue.clear();
				}
			}

			if (xm.getDatatype().equals("FML")) {
				Element fml = XMLTool.getChildElementByTagNameNS(xm.getDocument().getDocumentElement(), FML.E_FML, FML.namespaceURI);
				if( fml != null ) {
					Element backchannel = XMLTool.getChildElementByTagNameNS(fml, FML.E_BACKCHANNEL, FML.namespaceURI);
					if( backchannel != null ) {
						DMLogger.getLogger().log(meta.getTime(), "AgentAction:Backchannel" );
					}
				}
			}
			Document doc = xm.getDocument();
			Element event = XMLTool.getChildElementByLocalNameNS(doc.getDocumentElement(), SemaineML.E_EVENT, SemaineML.namespaceURI);
	    	if (event != null) {
	    		String id = event.getAttribute(SemaineML.A_ID);
	    		long time = Long.parseLong(event.getAttribute(SemaineML.A_TIME));
	    		String type = event.getAttribute(SemaineML.A_TYPE);
	    		if (type.equals(SemaineML.V_READY)) {
	    			String keyToRemove = null;
	    			for( String key : preparingResponses.keySet() ) {
	    				if( preparingResponses.get(key).equals(id) ) {
	    					DMLogger.getLogger().log(meta.getTime(), "AgentAction:UtterancePrepared");
	    					preparedResponses.put(key, id);
	    					keyToRemove = key;
	    					break;
	    				}
	    			}
	    			if( keyToRemove != null ) preparingResponses.remove(keyToRemove);
	    		} else if (type.equals(SemaineML.V_DELETED)) {
	    			String keyToRemove = null;
	    			for( String key : preparingResponses.keySet() ) {
	    				if( preparingResponses.get(key).equals(id) ) {
	    					keyToRemove = key;
	    					break;
	    				}
	    			}
	    			if( keyToRemove != null ) preparingResponses.remove(keyToRemove);
	    			
	    			keyToRemove = null;
	    			for( String key : preparedResponses.keySet() ) {
	    				if( preparedResponses.get(key).equals(id) ) {
	    					keyToRemove = key;
	    					break;
	    				}
	    			}
	    			if( keyToRemove != null ) preparedResponses.remove(keyToRemove);
	    		} else {
	    			// Do Nothing
	    		}
	    	}
		}

		/* Processes User state updates */
		if( m instanceof SEMAINEStateMessage ) {
			SEMAINEStateMessage sm = ((SEMAINEStateMessage)m);
			StateInfo stateInfo = sm.getState();
			StateInfo.Type stateInfoType = stateInfo.getType();

			switch (stateInfoType) {
			case UserState:
				/* Updates user speaking state (speaking or silent) */
				if( stateInfo.hasInfo("speaking") ) {
					if( stateInfo.getInfo("speaking").equals("true") ) {
						if( is.getString("User.speakingState") == null || !is.getString("User.speakingState").equals("speaking") ) {
							is.set("User.speakingState", "speaking");
							is.set("User.speakingStateTime", (int)meta.getTime());
							if(!isStoringFeatures) isStoringFeatures = true;
						}
					} else if( stateInfo.getInfo("speaking").equals("false") ) {
						if( is.getString("User.speakingState") == null || !is.getString("User.speakingState").equals("listening") ) {
							is.set("User.speakingState", "listening");
							is.set("User.speakingStateTime", (int)meta.getTime());
						}
					}
				}
				/* Updates detected emotions (valence, arousal, interest) */
				if( stateInfo.hasInfo("valence") ) {
					float valence = Float.parseFloat( stateInfo.getInfo("valence") );
					EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.VALENCE, valence );
					detectedEmotions.add( ee );
					summarizeEmotionEvents();
				} else if( stateInfo.hasInfo("arousal") ) {
					float arousal = Float.parseFloat( stateInfo.getInfo("arousal") );
					EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.AROUSAL, arousal );
					detectedEmotions.add( ee );
					summarizeEmotionEvents();
				} else if( stateInfo.hasInfo("interest") ) {
					float interest = Float.parseFloat( stateInfo.getInfo("interest") );
					EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.INTEREST, interest );
					detectedEmotions.add( ee );
					summarizeEmotionEvents();
				} else if( stateInfo.hasInfo("potency") ) {
					float potency = Float.parseFloat( stateInfo.getInfo("potency") );
					EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.POTENCY, potency );
					detectedEmotions.add( ee );
					summarizeEmotionEvents();
				} else if( stateInfo.hasInfo("intensity") ) {
					float intensity = Float.parseFloat( stateInfo.getInfo("intensity") );
					EmotionEvent ee = new EmotionEvent( meta.getTime(), 0, EmotionEvent.INTENSITY, intensity );
					detectedEmotions.add( ee );
					summarizeEmotionEvents();
				}

				/* Updates the detected and analysed user utterances */
				if( stateInfo.hasInfo("userUtterance") && stateInfo.hasInfo("userUtteranceStartTime") ) {
					String utterance = stateInfo.getInfo("userUtterance");
					long time = Long.parseLong(stateInfo.getInfo("userUtteranceStartTime"));
					String f = stateInfo.getInfo("userUtteranceFeatures");
					if( f == null ) {
						f = "";
					}
					DialogueAct act = new DialogueAct( utterance, time );
					act.setEndtime(meta.getTime());
					if( f.contains("positive") ) act.setPositive(true);
					if( f.contains("negative") ) act.setNegative(true);
					if( f.contains("disagree") ) {
						act.setDisagree(true);
						f = f.replace("disagree", "");
					}
					if( f.contains("agree") ) act.setAgree(true);
					if( f.contains("about_other_people") ) act.setAboutOtherPeople(true);
					if( f.contains("about_other_character") ) act.setAboutOtherCharacter(true);
					if( f.contains("about_current_character") ) act.setAboutCurrentCharacter(true);
					if( f.contains("about_own_feelings") ) act.setAboutOwnFeelings(true);
					if( f.contains("pragmatic") ) act.setPragmatic(true);
					if( f.contains("abou_self") ) act.setTalkAboutSelf(true);
					if( f.contains("future") ) act.setFuture(true);
					if( f.contains("past") ) act.setPast(true);
					if( f.contains("event") ) act.setEvent(true);
					if( f.contains("action") ) act.setAction(true);
					if( f.contains("laugh") ) act.setLaugh(true);
					if( f.contains("change_speaker") ) act.setChangeSpeaker(true);
					if( f.contains("target:") ) act.setTargetCharacter( f.substring(f.indexOf("target:")+7, Math.max(f.length(),f.indexOf(" ", f.indexOf("target:")+7))) );
					if( f.contains("short") ) act.setLength(DialogueAct.Length.SHORT);
					if( f.contains("long") ) act.setLength(DialogueAct.Length.LONG);

					if( detectedDActs.size() > 1 && Math.abs(detectedDActs.get(detectedDActs.size()-1).getStarttime() - act.getStarttime()) < 10 ) {
						// Same dialogueAct, so update the last DA in the list
						detectedDActs.set(detectedDActs.size()-1, act);
					} else {
						detectedDActs.add(act);
					}
					getCombinedUserDialogueAct();
				}

				break;
			case ContextState:
				/* Updates the current character and the user */
				/* Update user presence */
				if( stateInfo.hasInfo("userPresent") ) {
					if( is.getInteger("User.present") == null ) {
						is.set("User.present", 0);
					}
					System.out.println("XXXXXX " + is.getInteger("User.present"));
					if( stateInfo.getInfo("userPresent").equals("present") &&  is.getInteger("User.present") == 0 ) {
						DMLogger.getLogger().log(meta.getTime(), "System:SystemStarted" );
						is.set("User.present", 1);
						is.set("User.presentStateChanged",1);
						systemStarted = true;

					} else if( stateInfo.getInfo("userPresent").equals("absent") && is.getInteger("User.present") == 0 ) {
						DMLogger.getLogger().log(meta.getTime(), "System:SystemStopped" );
						is.set("User.present", 0);
						is.set("User.presentStateChanged",1);
						systemStarted = false;
					}
				}

				/* Update current character */
				if( stateInfo.hasInfo("character") ) {
					is.set("Agent.character", stateInfo.getInfo("character"));
					is.set("Agent.characterChanged", 1);
				}
				break;
			case AgentState:
				String intention = stateInfo.getInfo("turnTakingIntention");
				if( intention != null && intention.equals("startSpeaking") ) {
					String currSpeakingState = is.getString("Agent.speakingState");
					if( currSpeakingState != null && !currSpeakingState.equals("speaking") && !currSpeakingState.equals("preparing") ) {
						String userSpeakingState = is.getString("User.speakingState");
						if( userSpeakingState != null && userSpeakingState.equals("waiting") ) {
							is.set("Agent.speakingIntention","retake_turn");
						} else {
							is.set("Agent.speakingIntention","want_turn");
						}
					}
				}
				break;
			}
		}
		//is.print();
	}

	/**
	 * This method checks if the given message contains the start signal of the animation that the agent
	 * is going to play next.
	 * @param xm the message to check
	 * @return true if the message contains the start signal, false if it does not.
	 */
	public boolean speechStarted( SEMAINEXMLMessage xm )
	{
		Element callbackElem = XMLTool.getChildElementByLocalNameNS(xm.getDocument(), "callback", SemaineML.namespaceURI);
		if( callbackElem != null ) {
			Element eventElem = XMLTool.getChildElementByLocalNameNS(callbackElem, "event", SemaineML.namespaceURI);
			if( eventElem != null ) {
				String agentSpeakingState = is.getString("Agent.speakingState");
				if( agentSpeakingState != null ) {
					if( eventElem.hasAttribute("type") && eventElem.getAttribute("type").equals("start") && (agentSpeakingState.equals("preparing") || agentSpeakingState.equals("listening")) ) {
						return true;
					}
				} else {
					if( eventElem.hasAttribute("type") && eventElem.getAttribute("type").equals("start") ) {
						return true;
					}
				}
				
			}
		}
		return false;
	}

	/**
	 * This method checks if the given message contains the end signal of the animation that the agent
	 * is playing.
	 * @param xm the message to check
	 * @return true if the message contains the end signal, false if it does not.
	 */
	public boolean speechReady( SEMAINEXMLMessage xm ) throws JMSException
	{
		Element callbackElem = XMLTool.getChildElementByLocalNameNS(xm.getDocument(), "callback", SemaineML.namespaceURI);
		if( callbackElem != null ) {
			Element eventElem = XMLTool.getChildElementByLocalNameNS(callbackElem, "event", SemaineML.namespaceURI);
			if( eventElem != null ) {
				String agentSpeakingState = is.getString("Agent.speakingState");
				if( agentSpeakingState != null ) {
					if( eventElem.hasAttribute("type") && eventElem.getAttribute("type").equals("end") && (agentSpeakingState.equals("preparing") || agentSpeakingState.equals("speaking")) ) {
						return true;
					}
				} else {
					if( eventElem.hasAttribute("type") && eventElem.getAttribute("type").equals("end") ) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Summarizes the detected Emotional events and puts this in the InformationState
	 */
	public void summarizeEmotionEvents()
	{
		ArrayList<Float> highArousals = new ArrayList<Float>();
		ArrayList<Float> lowArousals = new ArrayList<Float>();
		ArrayList<Float> highValence = new ArrayList<Float>();
		ArrayList<Float> lowValence = new ArrayList<Float>();
		ArrayList<Float> highInterest = new ArrayList<Float>();
		ArrayList<Float> lowInterest = new ArrayList<Float>();
		ArrayList<Float> highPotency = new ArrayList<Float>();
		ArrayList<Float> lowPotency = new ArrayList<Float>();
		ArrayList<Float> highIntensity = new ArrayList<Float>();
		ArrayList<Float> lowIntensity = new ArrayList<Float>();
		for( EmotionEvent event : detectedEmotions ) {
			if( event.getType() == EmotionEvent.AROUSAL ) {
				if( event.getIntensity() >= 0 ) {
					highArousals.add(event.getIntensity());
				} else {
					lowArousals.add(event.getIntensity());
				}
			} else if( event.getType() == EmotionEvent.VALENCE ) {
				if( event.getIntensity() >= 0 ) {
					highValence.add(event.getIntensity());
				} else {
					lowValence.add(event.getIntensity());
				}
			} else if( event.getType() == EmotionEvent.INTEREST ) {
				if( event.getIntensity() >= 0 ) {
					highInterest.add(event.getIntensity());
				} else {
					lowInterest.add(event.getIntensity());
				}
			} else if( event.getType() == EmotionEvent.POTENCY ) {
				if( event.getIntensity() >= 0 ) {
					highPotency.add(event.getIntensity());
				} else {
					lowPotency.add(event.getIntensity());
				}
			} else if( event.getType() == EmotionEvent.INTENSITY ) {
				if( event.getIntensity() >= 0 ) {
					highIntensity.add(event.getIntensity());
				} else {
					lowIntensity.add(event.getIntensity());
				}
			}
		}
		
		setISEmotions("Valence", lowValence, highValence);
		setISEmotions("Arousal", lowArousals, highArousals);
		setISEmotions("Interest", lowInterest, highInterest);
		setISEmotions("Potency", lowPotency, highPotency);
		setISEmotions("Intensity", lowIntensity, highIntensity);
	}
	
	/**
	 * Based on the given emotion-name, and the list of low and high receives intensities, 
	 * calculate the min,max and average values, and put this in the InformationState.
	 *  
	 * @param emotion - the name of the Emotion this data belongs to
	 * @param nrLow - the number of low emotion-values (<0)
	 * @param nrHigh - the number of high emotion-values (>=0)
	 */
	public void setISEmotions( String emotion, ArrayList<Float> nrLow, ArrayList<Float> nrHigh )
	{
		if( nrLow.size() == 0 && nrHigh.size() == 0 ) {
			is.set("UserTurn."+emotion+".nrLowEvents", 0);
			is.set("UserTurn."+emotion+".nrHighEvents", 0);
			is.set("UserTurn."+emotion+".average", 0);
			is.set("UserTurn."+emotion+".min", 0);
			is.set("UserTurn."+emotion+".max", 0);
		} else {
			float sum = 0f;
			float max = -999f;
			float min = 999f;
			for( Float fl : nrHigh ) {
				if( fl > max ) max = fl;
				if( fl < min ) min = fl;
				sum = sum + fl;
			}
			for( Float fl : nrLow ) {
				if( fl > max ) max = fl;
				if( fl < min ) min = fl;
				sum = sum + fl;
			}
			is.set("UserTurn."+emotion+".nrLowEvents", nrLow.size());
			is.set("UserTurn."+emotion+".nrHighEvents", nrHigh.size());
			is.set("UserTurn."+emotion+".average", new Float(sum / (nrLow.size() + nrHigh.size())).doubleValue());
			is.set("UserTurn."+emotion+".min", new Double(min));
			is.set("UserTurn."+emotion+".max", new Double(max));
		}
	}
	
	/**
	 * Summarizes the detected AudioFeatures and puts this in the InformationState
	 */
	public void summarizeFeatures()
	{
		for( String featureName : detectedFeatures.keySet() ) {
			ArrayList<Float> features = detectedFeatures.get(featureName);
			if( features.size() == 0 ) {
				continue;
			} else {
				float min = 99999;
				float max = -99999;
				float avg = 0;
				for( float fl : features ) {
					avg = avg + fl;
					if( fl < min ) min = fl;
					if( fl > max ) max = fl;
				}
				avg = avg / features.size();
				
				is.set("UserTurn.AudioFeatures."+featureName+"_min",new Float(min).doubleValue());
				is.set("UserTurn.AudioFeatures."+featureName+"_max",new Float(max).doubleValue());
				is.set("UserTurn.AudioFeatures."+featureName+"_avg",new Float(avg).doubleValue());
			}
		}
	}

	/**
	 * Combines all DialogueActs of the last UserTurn, and puts this in the InformationState
	 */
	public void getCombinedUserDialogueAct()
	{
		DialogueAct act = null;
		for( int i=dActIndex; i<detectedDActs.size(); i++ ) {
			if( detectedDActs.get(i).getFeatures().length() > 0 ) {
				DMLogger.getLogger().log(meta.getTime(), "UserAction:ContentFeatures features="+detectedDActs.get(i).getFeatures().trim()+" starttime="+detectedDActs.get(i).getStarttime()+" endtime="+detectedDActs.get(i).getEndtime()+"");
			}
			if( act == null ) {
				if( i == dActIndex ) {
					act = detectedDActs.get(i);
				} else {
					// Something is wrong
					// TODO: report error
				}
			} else {
				act = new DialogueAct(act,detectedDActs.get(i));
			}
		}
		
		if( act == null ) return;
		
		is.set("UserTurn.utterance", act.getUtterance());
		is.set("UserTurn.starttime",(int)act.getStarttime());
		is.set("UserTurn.endtime",(int)act.getStarttime());
		is.set("UserTurn.targetCharacter",act.getTargetCharacter());
		is.remove("UserTurn.SemanticFeatures");
		for( String contentFeature : act.getFeatureList() ) {
			is.set("UserTurn.SemanticFeatures._addlast",contentFeature);
		}
	}
	
	/**
	 * Updates the InformationState with the behaviour that the Agent is performing now
	 */
	public void sendSpeaking()
	{
		is.set("Agent.speakingState","preparing");
		is.set("Agent.speakingIntention","null");
		is.set("ResponseHistory._addlast.id",latestResponse.getId());
		is.set("ResponseHistory._last.response",latestResponse.getResponse());
	}

	/**
	 * Adds the given data to the list of data to send to Semaine at the end of the Agent's turn
	 * 
	 * @param name - the name of the data-element to send.
	 * @param value - the value of the data-element to send.
	 * @param channel - the Semaine-channel to send the data too.
	 */
	public void sendDataAtEndOfTurn( String name, String value, String channel )
	{
		String[] queue = {name,value,channel};
		dataSendQueue.add(queue);
	}

	/**
	 * Sends the given data to the other Semaine components
	 * 
	 * @param name - the name of the data-element to send.
	 * @param value - the value of the data-element to send.
	 * @param channel - the Semaine-channel to send the data too (could be 'dialogstate' and 'contextstate')
	 */
	public void sendData( String name, String value, String channel ) throws JMSException
	{
		Map<String,String> info = new HashMap<String,String>();
		info.put(name,value);

		if( channel.toLowerCase().equals("dialogstate") ) {
			DialogStateInfo dsi = new DialogStateInfo(info, null);
			dialogStateSender.sendStateInfo(dsi, meta.getTime());
		} else if( channel.toLowerCase().equals("contextstate") ) {
			ContextStateInfo csi = new ContextStateInfo(info);
			contextSender.sendStateInfo( csi, meta.getTime() );
		}
	}
	
	/**
	 * Executes the Behaviour of a Template, with the given InformationState, and the given list of arguments.
	 * 
	 * @param is - the current InformationState
	 * @param argNames - the list of names of the given arguments
	 * @param argValues - the list of values of the given arguments
	 */
	public void execute( InformationState is, ArrayList<String> argNames, ArrayList<String> argValues )
	{
		if( argNames == null ) argNames = new ArrayList<String>();
		if( argValues == null ) argValues = new ArrayList<String>();
		
		Document doc = constructFMLDocument(argNames, argValues);
		if( doc == null ) return;
		
		String id = preparedResponses.get(currHash);
		
		if( id != null ) {
			/* The Response has been prepared before, so it only has to be started. */
			try {
				latestResponse = currResponse;
				sendSpeaking();
				commandSender.sendTextMessage("STARTAT 0\nPRIORITY 0.5\nLIFETIME 5000\n", meta.getTime(), Event.single, id, meta.getTime());
			}catch( JMSException e ){
				// TODO: handle
			}
		} else {
			/* The Response has not been prepared, so it has to be send directly. */
			String contentID = "fml_uap_"+output_counter;
			try {
				latestResponse = currResponse;
				sendSpeaking();
				//printDocument(doc);
				fmlSender.sendXML(doc, meta.getTime(), "bml_uap_"+output_counter, meta.getTime());
			}catch( JMSException e ) {
				// Handle
			}
			output_counter++;
		}
	}
	
	/**
	 * Executes the Behaviour of a Template, with the given InformationState, and the given list of arguments.
	 * 
	 * @param is - the current InformationState
	 * @param argNames - the list of names of the given arguments
	 * @param argValues - the list of values of the given arguments
	 */
	public void prepare( InformationState is, ArrayList<String> argNames, ArrayList<String> argValues ) 
	{
		if( preparingResponses.size() == 0 ) {
			if( argNames == null ) argNames = new ArrayList<String>();
			if( argValues == null ) argValues = new ArrayList<String>();
			
			Document doc = constructFMLDocument(argNames, argValues);
			if( doc == null ) return;
			
			String hash = currHash;
			if( preparingResponses.get(hash) != null || preparedResponses.get(hash) != null ) {
				return;
			}
			
			try {
				// TODO: Uncomment and test
				queuingFMLSender.sendXML(doc, meta.getTime(), "bml_uap_"+output_counter, meta.getTime());
				preparingResponses.put(hash, "bml_uap_"+output_counter);
				DMLogger.getLogger().log(meta.getTime(), "AgentAction:PrepareUtterance, utterance=" + getResponse(argNames, argValues).getResponse() );
			}catch( JMSException e ){
				// TODO Handle
			}
			
			output_counter++;
		}
	}
	
	/**
	 * Constructs a FML-document from the given arguments
	 * 
	 * @param argNames - the names of the given arguments.
	 * @param argValues - the values of the given arguments.
	 * @return the constructed FML-document.
	 */
	public Document constructFMLDocument( ArrayList<String> argNames, ArrayList<String> argValues )
	{
		Response response = getResponse(argNames, argValues);
		if( response == null ) return null;
		
		currHash = createHash(response,argNames,argValues);
		
		currResponse = response;
		String responseString = response.getResponse();
		argNames.addAll(response.getArgNames());
		argValues.addAll(response.getArgValues());
		
		Document doc = XMLTool.newDocument("fml-apml", null, FML.version);
		Element root = doc.getDocumentElement();

		Element bml = XMLTool.appendChildElement(root, BML.E_BML, BML.namespaceURI);
		bml.setAttribute(BML.A_ID, "bml_uap_"+output_counter); 
		Element fml = XMLTool.appendChildElement(root, FML.E_FML, FML.namespaceURI);
		fml.setAttribute(FML.A_ID, "fml_uap_"+output_counter);
		
		/* Speech Element */
		Element speech = XMLTool.appendChildElement(bml, BML.E_SPEECH);
		speech.setAttribute(BML.A_ID, "speech_uap_"+output_counter);
		speech.setAttribute(BML.E_TEXT, responseString);
		speech.setAttribute(BML.E_LANGUAGE, "en-GB");
		speech.setAttribute("voice", "activemary");
		
		/* Mark Elements */
		int counter=1;
		String[] words = responseString.split(" ");
		for( String word : words ) {
			Element mark = XMLTool.appendChildElement(speech, SSML.E_MARK, SSML.namespaceURI);
			mark.setAttribute(SSML.A_NAME, "speech_uap_"+output_counter+":tm"+counter);
			Node text = doc.createTextNode(word);
			speech.appendChild(text);
			counter++;
		}
		Element mark = XMLTool.appendChildElement(speech, SSML.E_MARK, SSML.namespaceURI);
		mark.setAttribute(SSML.A_NAME, "speech_uap_"+output_counter+":tm"+counter);
		
		/* Add the arguments to the FML-document */
		int tagCounter = 1;
		for( int i=0; i<argNames.size(); i++ ) {
			String argName = argNames.get(i);
			if( !argName.equals("response") && !argName.equals("response2") && argName.contains(":") ) {
				String[] tags = argName.split(":");
				if( tags.length == 2 ) {
					/* Determine start and endtime */
					String starttime = null;
					String endtime = null;
					String argValue = argValues.get(i);
					if( argValue.equals("_start") ) {
						starttime = "speech_uap_"+output_counter+":tm1";
						endtime = "speech_uap_"+output_counter+":tm"+(Math.min(3, counter));
					} else if( argValue.equals("_end") ) {
						starttime = "speech_uap_"+output_counter+":tm"+(Math.max(1,(counter-2)));
						endtime = "speech_uap_"+output_counter+":tm"+counter;
					} else if( argValue.equals("_all") ) {
						starttime = "speech_uap_"+output_counter+":tm1";
						endtime = "speech_uap_"+output_counter+":tm"+counter;
					} else {
						for( int j=0; j<words.length; j++ ) {
							if( words[j].equals(argValue) ) {
								starttime = "speech_uap_"+output_counter+":tm"+(j+1);
								endtime = "speech_uap_"+output_counter+":tm"+(j+2);
								break;
							}
						}
					}
					if( starttime == null || endtime == null ) continue;
					
					Element targetElement;
					if( tags[0].equals("pitchaccent") ) {
						targetElement = bml;
					} else {
						targetElement = fml;
					}
					
					Element tagElement = XMLTool.appendChildElement(targetElement, tags[0]);
					tagElement.setAttribute("id", "tag"+tagCounter);
					tagElement.setAttribute("type", tags[1]);
					tagElement.setAttribute("start", starttime);
					tagElement.setAttribute("end", endtime);
					tagElement.setAttribute("importance", ""+1);
					tagCounter++;
				}
			}
		}
		return doc;
	}

	/**
	 * Retrieves the Response from the given list of arguments. If two responses are present they are combined into 1.
	 * 
	 * @param argNames - the names of the given arguments.
	 * @param argValues - the values of the given arguments.
	 * @return the found Response
	 */
	public Response getResponse( ArrayList<String> argNames, ArrayList<String> argValues )
	{
		int responseIndex = argNames.indexOf("response");
		if( responseIndex == -1 ) {
			System.out.println("Error, no response given.");
			return null;
		}
		Response response = responses.get(argValues.get(responseIndex));
		if( response == null ) {
			ArrayList<Response> responseGroup = responseGroups.get(argValues.get(responseIndex));
			if( responseGroup == null ) {
				System.out.println("Error, fitting Response ("+argValues.get(responseIndex)+") could not be found.");
				return null;
			} else {
				response = responseGroup.get(random.nextInt(responseGroup.size()));
			}
		}
		
		int responseIndex2 = argNames.indexOf("response2");
		if( responseIndex2 == -1 ) {
			return response;
		}
		Response response2 = responses.get(argValues.get(responseIndex2));
		if( response2 == null ) {
			ArrayList<Response> responseGroup = responseGroups.get(argValues.get(responseIndex2));
			if( responseGroup == null ) {
				System.out.println("Error, fitting Response ("+argValues.get(responseIndex2)+") could not be found.");
				return response;
			} else {
				response2 = responseGroup.get(random.nextInt(responseGroup.size()));
			}
		}
		Response combinedResponse = new Response( response, response2 );
		return combinedResponse;
	}
	
	/**
	 * Creates a unique Hash from the given list of arguments, to make a unique identifier of this particular Response.
	 * 
	 * @param argNames - the names of the given arguments.
	 * @param argValues - the values of the given arguments.
	 * @return the Hash-string creates from the list of arguments.
	 */
	public String createHash( Response r, ArrayList<String> argNames, ArrayList<String> argValues )
	{	
		ArrayList<String> argNamesCopy = (ArrayList<String>)argNames.clone();
		ArrayList<String> argValuesCopy = (ArrayList<String>)argValues.clone();
		String hash = r.getResponse();
		Collections.sort(argNamesCopy);
		Collections.sort(argValuesCopy);
		
		ArrayList<String> nameToRemove = new ArrayList<String>();
		ArrayList<String> valueToRemove = new ArrayList<String>();
		for( int i=0; i<argNames.size(); i++ ) {
			if( argNames.get(i).startsWith("response") ) {
				nameToRemove.add(argNames.get(i));
				valueToRemove.add(argValues.get(i));
			}
		}
		for( String name : nameToRemove ) {
			argNamesCopy.remove(name);
		}
		for( String value : valueToRemove ) {
			argValuesCopy.remove(value);
		}
		
		for(String str : argNamesCopy ) {
			hash = hash + str;
		}
		for(String str : argValuesCopy ) {
			hash = hash + str;
		}
		return hash;
	}
	
	/**
	 * Prints the given Document to the Console (debugging-method).
	 *
	 * @param doc - the Document to print
	 */
	private String docToString( Document doc )
	{
		try {
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			
			//initialize StreamResult with File object to save to file
			StreamResult result = new StreamResult(new StringWriter());
			DOMSource source = new DOMSource(doc);
			transformer.transform(source, result);

			String xmlString = result.getWriter().toString();
			return xmlString;
		}catch( Exception e ){}
		return null;
	}
}
