/**
 * @author Dipen Patel
 * Student ID: 300304965
 */

package steam.boiler.core;

import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.util.Mailbox;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.Mailbox.Mode;
import steam.boiler.util.MemoryAnnotations.Initialisation;
import steam.boiler.util.SteamBoilerCharacteristics;

public class SteamBoilerController {
  /**
   * This is used to store the current state of the controller.
   */
  private Mailbox.Mode controllerMode = Mailbox.Mode.INITIALISATION;
  /**
   * This is used store incoming message mailbox.
   */
  private @Nullable Mailbox incomingMessage;
  /**
   * This is used store outgoing message mailbox.
   */
  private @Nullable Mailbox outgoingMessage;
  /**
   * This is used to store the steam boiler configuration.
   */
  private @Nullable SteamBoilerCharacteristics configuration;
  /**
   * This is used to store physical unit water level.
   */
  private double waterLevel;
  /**
   * This is used to store physical unit steam level.
   */
  private double steamLevel; // is this the steam coming out or in the boiler
  /**
   * This is used to store the state of the valves.
   */
  private boolean isValveOpen = false;
  /**
   * This is used to store the prevWaterLevel.
   */
  private double prevWaterLevel;
  /**
   * This is used to store the number of pumps need for each cycle.
   */
  private int numberOfPumps;
  /**
   * This is used to store the actual number of pumps not including the facility
   * ones.
   */
  private int numPumpsOn;
  /**
   * This is used to store the predicted water level which is used in rescue mode.
   */
  private double predictedWaterLevel;
  /**
   * This array is used store the previous state of the pump.
   */
  private boolean[] prevPumpState;
  /**
   * This array is used to store the pumps that are failure.
   */
  private int[] pumpFailures;
  /**
   * This array is used to store the pump controllers that are failure.
   */
  private int[] pumpControllerFailures;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration
   *          The boiler characteristics to be used.
   */
  public SteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    this.isValveOpen = false;
    this.predictedWaterLevel = configuration.getMinimalNormalLevel()
        + ((configuration.getMaximalNormalLevel() - configuration.getMinimalNormalLevel()) / 2);
    this.numPumpsOn = 0;
    this.pumpFailures = new int[configuration.getNumberOfPumps()];
    this.pumpControllerFailures = new int[configuration.getNumberOfPumps()];
    this.prevPumpState = new boolean[configuration.getNumberOfPumps()];
    intialisePumpState();
    intialisePumpNumber();
    initialisePumpFailuresArray();
    initialisePumpControllerFailuresArray();
  }

  /**
   * Initialize pumpFailuers array.
   */
  @Initialisation
  private void initialisePumpFailuresArray() {
   
    for (int i = 0; i < this.pumpFailures.length; i++) {
      this.pumpFailures[i] = -1;
    }
  }

  /**
   * Initialize pumpControllerFailures array.
   */
  @Initialisation
  private void initialisePumpControllerFailuresArray() {
   
    for (int i = 0; i < this.pumpControllerFailures.length; i++) {
      this.pumpControllerFailures[i] = -1;
    }
  }

  /**
   * Initialize prevPumpState array.
   */
  @Initialisation
  private void intialisePumpState() {
    for (int i = 0; i < this.prevPumpState.length; i++) {
      this.prevPumpState[i] = false;
    }
  }

  /**
   * This is helper method to set the initial number of pumps.
   */
  @Initialisation
  private void intialisePumpNumber() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    
    double totalPumpCapsity = 0;
    for (int i = 0; i < configuration1.getNumberOfPumps(); i++) {
      if (totalPumpCapsity < configuration1.getMaximualSteamRate()) {
        totalPumpCapsity = totalPumpCapsity + configuration1.getPumpCapacity(i);
      }
      if (totalPumpCapsity > configuration1.getMaximualSteamRate()) {
        this.numberOfPumps = i; // cause it less then int he pump loop
        break;
      }
    }
  }

  /**
   * Process a clock signal which occurs every 5 seconds. This requires reading
   * the set of incoming messages from the physical units and producing a set of
   * output messages which are sent back to them.
   *
   * @param incoming
   *          The set of incoming messages from the physical units.
   * @param outgoing
   *          Messages generated during the execution of this method should be
   *          written here.
   */
  public void clock(Mailbox incoming, Mailbox outgoing) {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    assert (incoming != null);
    assert (outgoing != null);
    
    this.outgoingMessage = outgoing;
    this.incomingMessage = incoming;
    if (isTransmissionFailure()) {
      // A transmission failure puts the program into the mode emergency
      // stop.
      this.controllerMode = Mailbox.Mode.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      return;
    }

    this.waterLevel = incoming.read(configuration1.getNumberOfPumps() * 2).getDoubleParameter();
    this.waterLevel = Math.round(this.waterLevel);
    this.steamLevel = incoming.read((configuration1.getNumberOfPumps() * 2) + 1)
          .getDoubleParameter();

    if (this.controllerMode == Mailbox.Mode.INITIALISATION) {
      initialisationMode();
      if (!incoming.contains(new Message(MessageKind.PHYSICAL_UNITS_READY))) {
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
      }
    } else if (this.controllerMode == Mailbox.Mode.NORMAL) {
      normalMode();
    } else if (this.controllerMode == Mailbox.Mode.DEGRADED) {
      degradedMode();
    } else if (this.controllerMode == Mailbox.Mode.RESCUE) {
      rescueMode();
    }

  }

  /**
   * This message is displayed in the simulation window, and enables a limited
   * form of debug output. The content of the message has no material effect on
   * the system, and can be whatever is desired. In principle, however, it should
   * display a useful message indicating the current state of the controller.
   *
   * @return the current mode of the controller
   */
  public String getStatusMessage() {
    String controllerState = this.controllerMode.name();
    assert (controllerState != null);
    
    return controllerState;
  }

  /**
   * This method is used to handle the initial mode.
   */
  private void initialisationMode() {
    // If the program realizes a failure of the water level detection unit
    // it enters the emergency stop mode.
    //NOTE: I named local variables with a 1 after it so that it would pass the error check
    // and so i could assert them.
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    if (checkWaterLevelMeasuringFailure()) {
      this.controllerMode = Mailbox.Mode.EMERGENCY_STOP;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      return;
    }

    // The program enters a state in which it waits for the message
    // STEAM-BOILER-WAITING to come from the physical units.
    if (incomingMessage1.contains(new Message(MessageKind.STEAM_BOILER_WAITING))) {
      // that is, when v is not equal to zero: v = the quantity of steam
      // exiting the boiler.
      // the program enters the emergency stop mode
      if (this.steamLevel != 0) {
        this.controllerMode = Mailbox.Mode.EMERGENCY_STOP;
        outgoingMessage1.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
        outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
        return;
      }
      fillBoiler();
    } else if (incomingMessage1.contains(new Message(MessageKind.PHYSICAL_UNITS_READY))) {
      // until it receives the signal PHYSICAL-UNITS-READY which must
      // necessarily be emitted by the physical units
      if (isPumpControllerFailure() || isPumpFailure()) {
        // mode degraded if any physical unit is defective.
        degradedMode();
        return;
      }

      // the program enters either the mode normal if all the physical
      // units operate correctly
      this.controllerMode = Mailbox.Mode.NORMAL;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
      return;

    }
  }

  /**
   * This method is used to handle normal mode.
   */
  private void normalMode() {
    if (isMeasuringUnitFailure() || isPumpFailure() || isPumpControllerFailure()
        || isWaterLevelNotWithinLimit()) {
      // As soon as the program recognizes a failure of the water level
      // measuring unit it goes into rescue mode.
      // Failure of any other physical unit puts the program into degraded
      // mode.

      // If the water level is risking reaching one of the limit values Ml
      // or M2 the program enters the mode emergency stop.
      return;
    }

    maintainWaterLevel();
  }

  /**
   * This method is used to handle degraded mode.
   */
  private void degradedMode() {
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    if (checkWaterLevelMeasuringFailure() && !checkSteamLevelMeasureFailure()) {
      this.controllerMode = Mailbox.Mode.RESCUE;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
      this.predictedWaterLevel = this.prevWaterLevel;
      rescueMode();
      return;
    } else if (incomingMessage1.contains(
        new Message(MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT))) {

      boolean waterLevelFailure = checkWaterLevelMeasuringFailure();
      if (waterLevelFailure) {
        this.controllerMode = Mailbox.Mode.EMERGENCY_STOP;
        outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
        return;
      }
    } else if (incomingMessage1
        .contains(new Message(MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n, checkPumpFailure()))) {

      if (isWaterLevelNotWithinLimit()) {
        return;
      }
      // maintain a satisfactory water level despite of the presence of
      // failure of some physical unit
      maintainWaterLevel();
    } else if (isWaterLevelNotWithinLimit()) {
      return;
    } else if (isSensorsRepairedNormal()) {
      this.controllerMode = Mailbox.Mode.NORMAL;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
    }

  }

  /**
   * This method is used to handle rescue mode.
   */
  private void rescueMode() {
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    int pumpContollerFailure = checkPumpControllerFailure();
    if (checkSteamLevelMeasureFailure() || pumpContollerFailure != -1) {
      this.controllerMode = Mailbox.Mode.EMERGENCY_STOP;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      return;

    } else if (isSensorsRepairedDegrade()) {
      this.controllerMode = Mode.DEGRADED;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      return;
    } else if (isSensorsRepairedNormal()) {
      this.controllerMode = Mode.NORMAL;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
      return;
    }

    if (incomingMessage1.contains(new Message(MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT))) {
      maintainRescueWaterLevel();
    }

  }



  /**
   * This method is used to check all the cases that allow the controller to
   * return to normal mode from degraded and rescue. if the a cause holds it will
   * return true else false.
   * 
   * @return true if controller is able to return to normal mode else false
   */
  private boolean isSensorsRepairedNormal() {
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    if (checkPumpRepaired() != -1) {
      if (checkPumpControllerRepaired() != -1) {
        if (incomingMessage1.contains(new Message(Mailbox.MessageKind.STEAM_REPAIRED))) {
          // case where pump controller and steam sensors are all fixed and there are no
          // more broken units.
          if (isAllPumpsFixed() && isAllControllersFixed()) {
            outgoingMessage1.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
            return true;
          }
        } else if (!checkSteamLevelMeasureFailure()) {
          // case where the steam sensor was never broken only pump and controller and
          // there are no more broken units.
          if (isAllPumpsFixed() && isAllControllersFixed()) {
            return true;
          }
        }
      } else if (incomingMessage1.contains(new Message(Mailbox.MessageKind.STEAM_REPAIRED))
          && isAllControllersFixed()) {
        // case where only pump and sensor failure and all units are fixed;
        if (isAllPumpsFixed()) {
          outgoingMessage1.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
          return true;
        }
      } else if (isAllControllersFixed() && !checkSteamLevelMeasureFailure()) {
        // case where there is no controller or steam sensor failure only pump failure.
        // and all the units are fixed.
        if (isAllPumpsFixed()) {
          return true;
        }
      } else if (isAllPumpsFixed() && incomingMessage1.contains(
          new Message(Mailbox.MessageKind.LEVEL_REPAIRED))) {
        // case used in rescue mode where if a pump is fixed then check that all pumps
        // are fixed else it should go to degrade mode
        outgoingMessage1.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
        return true;
      }
    } else if (checkPumpControllerRepaired() != -1) {
      if (incomingMessage1.contains(
          new Message(Mailbox.MessageKind.STEAM_REPAIRED)) && isAllPumpsFixed()) {
        // case where only the steam sensor and controller are repaired pump are all
        // working.
        if (isAllControllersFixed()) {
          outgoingMessage1.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
          return true;
        }
      } else if (isAllPumpsFixed() && !checkSteamLevelMeasureFailure()) {
        // case where only the controller was broken and all the units are repaired.
        if (isAllControllersFixed()) {
          return true;
        }
      }
    } else if (incomingMessage1.contains(
        new Message(Mailbox.MessageKind.STEAM_REPAIRED)) && isAllControllersFixed()
        && isAllPumpsFixed()) {
      // case were only the steam sensor was broken and pump controller and pump units
      // are all working
      outgoingMessage1.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
      return true;

    } else if (incomingMessage1.contains(
        new Message(Mailbox.MessageKind.LEVEL_REPAIRED)) && isAllPumpsFixed()) {
      // case where only the level sensor is broken and pumps are all working
      // correctly only used in rescue mode
      outgoingMessage1.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
      return true;
    }
    return false;
  }


  /**
   * Checks whether the required sensors are repaired in order to go back into
   * Degraded Mode.
   * 
   * @return true of controller is able to return to degraded mode else false.
   */
  private boolean isSensorsRepairedDegrade() {
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    if (incomingMessage1.contains(
        new Message(Mailbox.MessageKind.LEVEL_REPAIRED)) && incomingMessage1
        .contains(new Message(Mailbox.MessageKind.PUMP_FAILURE_DETECTION_n, checkPumpFailure()))) {
      outgoingMessage1.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
      return true;
    } else if (checkPumpFailure() != -1 && incomingMessage1.contains(
        new Message(MessageKind.LEVEL_REPAIRED))) {
      outgoingMessage1.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
      return true;
    } else if (!isAllPumpsFixed() && incomingMessage1.contains(
        new Message(MessageKind.LEVEL_REPAIRED))) {
      outgoingMessage1.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
      return true;
    }
    return false;
  }

  /**
   * Helper method to check for the pump controller that was repaired. Return the
   * id of the repaired pump controller else return -1.
   * 
   * @return the id of the repaired pump controller else -1.
   */
  private int checkPumpControllerRepaired() {
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    for (int i = 0; i < incomingMessage1.size(); i++) {
      if (incomingMessage1.read(i).getKind().equals(MessageKind.PUMP_CONTROL_REPAIRED_n)) {
        outgoingMessage1.send(new Message(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n,
            incomingMessage1.read(i).getIntegerParameter()));
        this.pumpControllerFailures[incomingMessage1.read(i).getIntegerParameter()] = -1;
        return incomingMessage1.read(i).getIntegerParameter();
      }
    }
    return -1;
  }

  /**
   * Helper method to check for the pump controller that was repaired. Return the
   * id of the repaired pump else return -1.
   * 
   * @return the id of the repaired pump else -1.
   */
  private int checkPumpRepaired() {
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    for (int i = 0; i < incomingMessage1.size(); i++) {
      if (incomingMessage1.read(i).getKind().equals(MessageKind.PUMP_REPAIRED_n)) {
        outgoingMessage1.send(new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n,
            incomingMessage1.read(i).getIntegerParameter()));
        this.pumpFailures[incomingMessage1.read(i).getIntegerParameter()] = -1;
        return incomingMessage1.read(i).getIntegerParameter();
      }
    }
    return -1;
  }
  
  /**
   * This is a helper method that will check that all the pumps are repaired. it
   * will return false if a pump is still broken else true.
   * 
   * @return return false if a pump is broken else true
   */
  private boolean isAllPumpsFixed() {
    for (int i = 0; i < this.pumpFailures.length; i++) {
      if (this.pumpFailures[i] != -1) {
        return false;
      }
    }
    return true;
  }

  /**
   * This is the helper method will check that all the controller are repaired. it
   * will return false if the a controller is still broken else true.
   * 
   * @return return false if a pump controller is broken else true
   */
  private boolean isAllControllersFixed() {
    for (int i = 0; i < this.pumpControllerFailures.length; i++) {
      if (this.pumpControllerFailures[i] != -1) {
        return false;
      }
    }
    return true;
  }

  /**
   * This helper method is used in the initial stage to fill the boiler to correct
   * level of water and then send the program ready message.
   */
  private void fillBoiler() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    boolean pumpStatus = getPumpStatus();
    if (this.waterLevel >= configuration1.getMaximalNormalLevel()) {
      // If the quantity of water in the steam-boiler is above N2 the
      // program activates the valve of the steam-boiler in order to empty
      // it.
      if (this.isValveOpen == false) {
        outgoingMessage1.send(new Message(MessageKind.VALVE));
        this.isValveOpen = true;
      }
      closePumps(configuration1.getNumberOfPumps());
    } else if (this.waterLevel <= configuration1.getMinimalNormalLevel()) {
      // If the quantity of water in the steam boiler is below N1 then
      // the program activates a pump to fill the steam-boiler.
      if (this.isValveOpen == true) {
        outgoingMessage1.send(new Message(MessageKind.VALVE));
        this.isValveOpen = false;
      }
      openPumps(configuration1.getNumberOfPumps());
    } else if (this.waterLevel <= configuration1.getMaximalNormalLevel()
        && this.waterLevel >= configuration1.getMinimalNormalLevel()) {
      if (pumpStatus) {
        if (this.isValveOpen == true) {
          outgoingMessage1.send(new Message(MessageKind.VALVE));
          this.isValveOpen = false;
        }
        closePumps(configuration1.getNumberOfPumps());
      } else {
        // as a level of water between NI and N2 has been reached the
        // program can send continuously the signal PROGRAM-READY
        outgoingMessage1.send(new Message(MessageKind.PROGRAM_READY));
      }
    }
  }

  /**
   * This is a helper method used to maintain water level between N1 and N2.
   */
  private void maintainWaterLevel() {
    // maintain the water level in the steam-boiler between NI and N2 with
    // all physical units operating correctly.
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
   
    double maxSteamRate = configuration1.getMaximualSteamRate() * 5;
    double totalPumpCapacity = totalPumpsCapacity(configuration1.getNumberOfPumps());
    assert (totalPumpCapacity >= 0);
    // As soon as the water level is below NI or above N2 the level can be
    // adjusted by the program by switching the pumps on or off.

    if (this.waterLevel + totalPumpCapacity <= configuration1.getMaximalNormalLevel()
        && (this.waterLevel - maxSteamRate - 3) >= configuration1.getMinimalNormalLevel()) {
      if (this.isValveOpen == true) {
        outgoingMessage1.send(new Message(MessageKind.VALVE));
        this.isValveOpen = false;
      }

      decrementPumpNumber();
      assert (this.numberOfPumps != this.numberOfPumps + 1);
      if (this.numberOfPumps == 0) {
        if (this.pumpFailures[0] == -1) {
          this.numPumpsOn--;
          Integer index = Integer.valueOf(0);
          assert (index != null);
          this.prevPumpState[0] = false;
          outgoingMessage1.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
        }
      }
      closePumps(this.numberOfPumps);
    } else if (this.waterLevel + totalPumpCapacity >= configuration1.getMaximalNormalLevel()) {
      decrementPumpNumber();
      assert (this.numberOfPumps != this.numberOfPumps + 1);
      // this was put cause it won't enter the loop if the pump id is 0
      // need to find a better way but this works for now
      if (this.isValveOpen == false) {
        outgoingMessage1.send(new Message(MessageKind.VALVE));
        this.isValveOpen = true;
      }
      if (this.numberOfPumps == 0) {
        if (this.pumpFailures[0] == -1) {
          this.numPumpsOn--;
          this.prevPumpState[0] = false;
          outgoingMessage1.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
        }
      }
      closePumps(this.numberOfPumps);
    } else if ((this.waterLevel - maxSteamRate - 3) <= configuration1.getMinimalNormalLevel()) {
      if (this.isValveOpen == true) {
        outgoingMessage1.send(new Message(MessageKind.VALVE));
        this.isValveOpen = false;
      }
      increamentPumpNumber();
      assert (this.numberOfPumps != this.numberOfPumps - 1);
      if (this.numberOfPumps == 0) {
        if (this.pumpFailures[0] == -1
            || (this.pumpFailures[0] != -1 && incomingMessage1.read(0).getBooleanParameter())) {
          this.numPumpsOn++;
          this.prevPumpState[0] = true;
          outgoingMessage1.send(new Message(MessageKind.OPEN_PUMP_n, 0));
        }
      }
      openPumps(this.numberOfPumps);
    }
    this.prevWaterLevel = this.waterLevel;
  }

  /**
   * This is a helper method that will maintain the water level for rescue mode
   * based on a predicated water value.
   */
  private void maintainRescueWaterLevel() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    double maxSteamRate = this.steamLevel * 5;
    double totalPumpCapacity = totalPumpsCapacity(configuration1.getNumberOfPumps());
    assert (totalPumpCapacity >= 0);
    if (this.predictedWaterLevel - maxSteamRate < configuration1.getMinimalNormalLevel()) {
      if (this.numberOfPumps < configuration1.getNumberOfPumps()) {
        this.numberOfPumps++;
      }
      if (this.numberOfPumps == 0) {
        if (this.pumpFailures[0] == -1
            || (this.pumpFailures[0] != -1 && incomingMessage1.read(0).getBooleanParameter())) {
          this.numPumpsOn++;
          this.prevPumpState[0] = true;
          outgoingMessage1.send(new Message(MessageKind.OPEN_PUMP_n, 0));
        }
      }
      openPumps(this.numberOfPumps);
    } else if (this.predictedWaterLevel + totalPumpCapacity 
        > configuration1.getMaximalNormalLevel()) {
      if (this.numberOfPumps >= 1) {
        this.numberOfPumps--;
      }
      if (this.numberOfPumps == 0) {
        if (this.pumpFailures[0] == -1) {
          this.numPumpsOn--;
          this.prevPumpState[0] = false;
          outgoingMessage1.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
        }
      }
      closePumps(this.numberOfPumps);
    }
    this.predictedWaterLevel = this.predictedWaterLevel - (maxSteamRate);
    this.predictedWaterLevel = this.predictedWaterLevel + totalPumpsCapacity(this.numPumpsOn) * 5;
  }

  /**
   * This is a helper method thats used to increment the pump number.
   */
  private void increamentPumpNumber() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    
    if (this.waterLevel < this.prevWaterLevel 
        && this.numberOfPumps <= configuration1.getNumberOfPumps()) {
      this.numberOfPumps++;
    }
  }

  /**
   * This is a helper method thats used to decrement pump numbers.
   */
  private void decrementPumpNumber() {
    if (this.waterLevel > this.prevWaterLevel && this.numberOfPumps >= 1) {
      this.numberOfPumps--;
    }
  }

  /**
   * This helper method is used to open the pumps given the number of pumps.
   * 
   * @param numPumps is the number of pump that need to be opened
   */
  private void openPumps(int numPumps) {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    

    assert (numPumps >= 0 && numPumps <= configuration1.getNumberOfPumps());
    this.numPumpsOn = 0;
    for (int i = 0; i < numPumps; i++) {
      if (this.pumpFailures[i] == -1
          || (this.pumpFailures[i] != -1 && incomingMessage1.read(i).getBooleanParameter())) {
        this.numPumpsOn++;
        Integer index = Integer.valueOf(i);
        assert (index != null);
        this.prevPumpState[i] = true;
        outgoingMessage1.send(new Message(MessageKind.OPEN_PUMP_n, i));
      }
    }
  }

  /**
   * This helper method is used to close the pumps given the number of pumps.
   * 
   * @param numPumps is the number of pumps that need to be closed
   */
  private void closePumps(int numPumps) {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    assert (numPumps >= 0 && numPumps <= configuration1.getNumberOfPumps());
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
 
    int tempNumPumps = numPumps;
    for (int i = 0; i < numPumps; i++) {
      if (tempNumPumps != 0) {
        if (this.pumpFailures[i] == -1) {
          this.numPumpsOn--;
          Integer index = Integer.valueOf(i);
          assert (index != null);
          this.prevPumpState[i] = false;
          outgoingMessage1.send(new Message(MessageKind.CLOSE_PUMP_n, i));
          tempNumPumps--;
        }
      }
    }
  }

  /**
   * This is a helper method that will return the status of the pump rather they
   * are on in which case it will return true else it will return false.
   * 
   * @return true if a pumps is on else false
   */
  private boolean getPumpStatus() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    for (int i = 0; i < configuration1.getNumberOfPumps(); i++) {
      if (incomingMessage1.read(i).getBooleanParameter()) {
        return true;
      }
    }
    return false;
  }

  /**
   * This helper method will check if there is a failure with the physical units
   * if there is a failure then it will return true else it will return false.
   * 
   * @return
   */
  public boolean isMeasuringUnitFailure() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    
    boolean steamSenorFailure = checkSteamLevelMeasureFailure();
    boolean waterLevelFailure = checkWaterLevelMeasuringFailure();

    if ((waterLevelFailure && this.controllerMode == Mailbox.Mode.INITIALISATION)
        || (waterLevelFailure && steamSenorFailure)) {

      this.controllerMode = Mode.EMERGENCY_STOP;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      return true;
    } else if (steamSenorFailure && this.controllerMode != Mailbox.Mode.INITIALISATION
        && isWaterLevelNotWithinLimit()) {
      // could dump this into the degrade mode like before
      this.controllerMode = Mode.EMERGENCY_STOP;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      return true;
    } else if (steamSenorFailure && this.controllerMode != Mailbox.Mode.INITIALISATION) {
      // Failure of any other physical unit puts the program into degraded
      // mode. - normal mode
      this.controllerMode = Mode.DEGRADED;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      return true;
    } else if (waterLevelFailure && this.controllerMode == Mailbox.Mode.NORMAL) {
      // As soon as the program recognizes a failure of the water level
      // measuring unit it goes into rescue mode.
      this.controllerMode = Mailbox.Mode.RESCUE;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
      this.predictedWaterLevel = this.prevWaterLevel;
      rescueMode();
      return true;
    }
    return false;
  }

  /**
   * This is a helper method checks if there is a failure with the water unit, if
   * there is it will return true, else false.
   * 
   * @return true if there is a failure else false
   */
  private boolean checkWaterLevelMeasuringFailure() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    
    if (this.waterLevel < 0 || this.waterLevel >= configuration1.getCapacity()) {
      outgoingMessage1.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
      return true;
    }
    return false;
  }

  /**
   * This is a helper method checks if there is a failure with the water unit, if
   * there is it will return true, else false.
   * 
   * @return true if there is a failure else false
   */
  private boolean checkSteamLevelMeasureFailure() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    if (this.steamLevel < 0 || this.steamLevel > configuration1.getMaximualSteamRate()) {
      outgoingMessage1.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
      return true;
    }
    return false;
  }

  /**
   * This is a helper method that will respond accordingly if a pumps is to fail.
   * in this cause it will also return true else if all the pumps are working
   * correctly it will return false.
   * 
   * @return true if there is a pump failure else false
   */
  private boolean isPumpFailure() {
    // Failure of any other physical unit puts the program into degraded
    // mode. - for normal mode
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    int pumpFailure = checkPumpFailure();
    if (pumpFailure != -1) {
      this.controllerMode = Mode.DEGRADED;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      outgoingMessage1.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, pumpFailure));
      return true;
    }
    return false;
  }

  /**
   * This helper method will check if there is a pump failure if there is a pump
   * failure then it will return the number of the pump that failed else it will
   * return -1 if all pumps are working correctly.
   * 
   * @return -1 if all pumps are working correctly else the broken pump number
   */
  private int checkPumpFailure() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);

    for (int i = 0; i < configuration1.getNumberOfPumps(); i++) {
      // tried to close it but is still open
      if (!this.prevPumpState[i]) {
        if (incomingMessage1.read(i).getBooleanParameter() != false) {
          this.pumpFailures[i] = i;
          return i;
        }
      } else {
        // tried to open it but is still closed
        if (this.prevPumpState[i]) {
          if (incomingMessage1.read(i).getBooleanParameter() != true) {
            this.pumpFailures[i] = i;
            return i;
          }
        }
      }
    }
    return -1;
  }

  /**
   * This is a helper method that will respond accordingly to a pump controller
   * failure. in the cause that there where a pump controller failure is found it
   * will return true else it will false.
   * 
   * @return true if there is a pump controller failure else false
   */
  private boolean isPumpControllerFailure() {
    // Failure of any other physical unit puts the program into degraded
    // mode. - for Normal Mode
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    int checkControllerFailure = checkPumpControllerFailure();
    if (checkControllerFailure != -1) {
      this.controllerMode = Mode.DEGRADED;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
      return true;
    }
    return false;
  }

  /**
   * this is a helper method that will check if there is failure with one of the
   * pump controllers, if there is a failure with one of the pump controllers then
   * it will return the pump controllers number else if will return -1.
   * 
   * @return -1 if all pumps controllers are working correctly else the broken
   *         pump controller number
   */
  private int checkPumpControllerFailure() {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    for (int i = 0; i < configuration1.getNumberOfPumps(); i++) {
      boolean tempPump = incomingMessage1.read(i).getBooleanParameter();
      boolean tempController = incomingMessage1.read(configuration1.getNumberOfPumps() + i)
          .getBooleanParameter();
      if (tempPump != tempController) {
        outgoingMessage1.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
        this.pumpControllerFailures[i] = i;
        return i;
      }
    }
    return -1;
  }

  /**
   * This helper method will check if the water level is above or below M1 and M2.
   * if it is then it will change the controller to emergency stop state.
   * 
   * @return return true if the water is not within M1 or M2 else false.
   */
  private boolean isWaterLevelNotWithinLimit() {
    // If the water level is risking reaching one of the limit values Ml
    // or M2 the program enters the mode emergency stop. - for Normal Mode
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    if (this.waterLevel < configuration1.getMinimalLimitLevel()
        || this.waterLevel > configuration1.getMaximalLimitLevel()) {
      this.controllerMode = Mailbox.Mode.EMERGENCY_STOP;
      outgoingMessage1.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
      return true;
    }
    return false;
  }

  /**
   * This method will check if there is a transmission error given a message kind,
   * it will return true if there is a transmission error else false.
   * 
   * @param messageKind is the message type that needs to be checked for transmission failure
   * @return true if a transmission failure is detected else false.
   */
  private boolean checkTransmissionFailure(MessageKind messageKind) {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    Mailbox outgoingMessage1 = this.outgoingMessage;
    assert (outgoingMessage1 != null);
    Mailbox incomingMessage1 = this.incomingMessage;
    assert (incomingMessage1 != null);
    
    int correctPumpNumber = 0;
    int correctPumpControllerNumber = 0;
    for (int i = 0; i < incomingMessage1.size(); i++) {
      if (incomingMessage1.read(i).getKind() == messageKind 
          && messageKind != Mailbox.MessageKind.PUMP_STATE_n_b
          && messageKind != Mailbox.MessageKind.PUMP_CONTROL_STATE_n_b) {
        return false;
      } else if (messageKind == Mailbox.MessageKind.PUMP_STATE_n_b) {
        if (incomingMessage1.read(i).getKind() == messageKind) {
          correctPumpNumber++;
        }
      } else if (messageKind == Mailbox.MessageKind.PUMP_CONTROL_STATE_n_b) {
        if (incomingMessage1.read(i).getKind() == messageKind) {
          correctPumpControllerNumber++;
        }
      }
    }
    if ((configuration1.getNumberOfPumps() != correctPumpNumber
        && messageKind == Mailbox.MessageKind.PUMP_STATE_n_b)
        || (configuration1.getNumberOfPumps() != correctPumpControllerNumber
            && messageKind == Mailbox.MessageKind.PUMP_CONTROL_STATE_n_b)) {
      return true;
    } else if ((configuration1.getNumberOfPumps() == correctPumpNumber
        && messageKind == Mailbox.MessageKind.PUMP_STATE_n_b)
        || (configuration1.getNumberOfPumps() == correctPumpControllerNumber
            && messageKind == Mailbox.MessageKind.PUMP_CONTROL_STATE_n_b)) {
      return false;
    }
    return true;
  }

  /**
   * This is a helper method that will return true if there is a transmission
   * failure, else false.
   * 
   * @return true if there is a transmission failure else false
   */
  private boolean isTransmissionFailure() {
    // A transmission failure puts the program into the mode emergency stop.
    if (checkTransmissionFailure(MessageKind.LEVEL_v)) {
      return true;
    } else if (checkTransmissionFailure(MessageKind.STEAM_v)) {
      return true;
    } else if (checkTransmissionFailure(MessageKind.PUMP_STATE_n_b)) {
      return true;
    } else if (checkTransmissionFailure(MessageKind.PUMP_CONTROL_STATE_n_b)) {
      return true;
    }
    return false;
  }
  
  /**
   * This is a helper method to calculates the total capacity for a give number of
   * pumps.
   * 
   * @return the total capacity for a given number of pumps
   */
  private double totalPumpsCapacity(int numPumps) {
    SteamBoilerCharacteristics configuration1 = this.configuration;
    assert (configuration1 != null);
    assert (numPumps >= 0 && numPumps <= configuration1.getNumberOfPumps());

    double totalCapacity = 0;
    for (int i = 0; i < numPumps; i++) {
      totalCapacity += configuration1.getPumpCapacity(i);
    }
    assert (totalCapacity >= 0);
    return totalCapacity;
  }
}
