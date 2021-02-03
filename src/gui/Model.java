package gui;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPin;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinEdge;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CFactory;
import com.pi4j.io.i2c.I2CFactory.UnsupportedBusNumberException;

import gui.Model.GuiStatus;
import raspi.hardware.i2c.ArduinoI2C;
import raspi.hardware.i2c.MotorDriverHAT;

// Vgl. https://www.baeldung.com/java-observer-pattern
// auch https://wiki.swechsler.de/doku.php?id=java:allgemein:mvc-beispiel
// http://www.nullpointer.at/2011/02/06/howto-gui-mit-swing-teil-4-interaktion-mit-der-gui/
// http://www.javaquizplayer.com/blogposts/java-propertychangelistener-as-observer-19.html
// TableModel...
// Vgl.: https://examples.javacodegeeks.com/core-java/java-swing-mvc-example/
/**
 * <p>
 * Das Model haelt die Zustandsgroessen..
 * </p>
 * <p>
 * Fuer jede Zustandsgroesse des Modells wird ein Attribut eingefuehrt.  
 * </p>
 * <p>
 * <ul>
 *  <li>Zaehler counter</li>
 *  <li>Taktdauer cycleTime</li>
 * </ul>
 * </p>
 * 
 */
public class Model 
{
    /**
     * logger
     */
    private final static Logger logger = LoggerFactory.getLogger(Model.class);
    
    /**
     * Kennung isRaspi kennzeichnet, der Lauf erfolgt auf dem RasberryPi.
     * Die Kennung wird zur Laufzeit aus den Systemvariablen fuer das
     * Betriebssystem und die Architektur ermittelt. Mit dieser Kennung kann
     * die Beauftragung von Raspi-internen Programmen gesteuert werden.
     */
    private final boolean isRaspi;
    /**
     * OS_NAME_RASPI = "linux" - Kennung fuer Linux.
     * <p>
     * ...wird verwendet, um einen Raspi zu erkennen...
     * </p>
     */
    public final static String OS_NAME_RASPI = "linux";
    /**
     * OS_ARCH_RASPI = "arm" - Kennung fuer die ARM-Architektur.
     * <p>
     * ...wird verwendet, um einen Raspi zu erkennen...
     * </p>
     */
    public final static String OS_ARCH_RASPI = "arm";

    /**
     * MD_HAT_ADDRESS - Bus-Adresse des MotorDriverHAT, festgelegt durch
     * Verdrahtung auf dem Baustein, Standard-Vorbelegung lautet: 0x40.
     * <p>
     * Vergleiche "MotorDriver HAT User Manual": ...The address range from 0x40 to 0x5F. 
     *  </p>
     */
    public final static int MD_HAT_ADDRESS = 0x40; 
    
    /**
     * MD_HAT_FREQUENCY = 100
     */
    public final static int MD_HAT_FREQUENCY = 100;

    /**
     * motorDriverHAT - Referenz auf den MotorDriverHAT...
     */
    private final MotorDriverHAT motorDriverHAT;
    
    /**
     * counter - Taktzaehler (keine weitere funktionale Bedeutung)
     * <p>
     * Der <b><code>counter</code></b> dient zur Zuordnung der Zustandsgroessen
     * aus  Data(). Mit jeder Taktung (Beauftragung durch den Arduino) wird
     * der Zaehler weitergezaehlt. Er hat erst einmal keine weitere Bedeutung
     * in der Kommunikation mit dem Arduino.
     * </p>
     */
    private long counter = 0L;
    
    /**
     * cycleTime - Zykluszeit (Taktzeit der Beauftragung durch den Arduino), 
     * wird durch Differenzbildung (vgl. this.past) ermittelt...
     */
    private BigDecimal cycleTime = BigDecimal.ZERO;
    
    /**
     * Instant past - der letzter Zeitstempel...
     * <p>
     * Der Takt wird durch den ArduinoI2C Uno vorgegeben. 
     * In past wird der letzte Zeitstempel abgelegt 
     * zur Bestimmung der Taktdauer T zwischen now und this.past. 
     * </p>
     */
    private Instant past = null;
    
    /**
     * Referenz auf den GPIO-controller...
     * <p>
     * Der GPIO-Controller bedient die GPIO-Schnittstelle des Raspi.
     * </p>
     */
    private final GpioController gpioController;

    /**
     * ARDUINO_ADDRESS - Bus-Adresse des ArduinoI2C, 
     * festgelegt durch Software auf dem ArduinoI2C... 
     */
    public final static int ARDUINO_ADDRESS = 0x08; 
       
    /**
     * arduinoI2C - Referenz auf Hilfsklasse zur Kommunikation 
     * mit dem Arduino. 
     * <p>
     * Raspberry ist der I2C-Master, Arduino der
     * I2C-Slave, angestossen wird die Kommunikation aber durch
     * einen Takt durch den Arduino (Zykluszeit auf dem Arduino
     * einstellbar).
     * </p>
     */
    private final ArduinoI2C arduinoI2C;
    
    /**
     * i2cStatus - Status der Kommunikation mit dem Arduino
     * <p>
     * <ul>
     * <li><b>NOP</b> - Keine Kommunikation, z.B. nach Programmstart, vor Start-Button</li>
     * <li><b>INITIAL</b> - Erste Beauftragung, Raspi ist zur Kommunikation bereit, nach Start-Button, als token wird 0 gesendet</li>
     * <li><b>SUCCESS</b> - Erfolgreiche Erstbeauftragung, der token wird jeweils im Arduino erhoeht...</li>
     * <li><b>ERROR</b> - Fehler </li>
     * </ul>
     * </p>
     */
    private ArduinoI2C.Status i2cStatus = ArduinoI2C.Status.NOP;
    
    /**
     * token - Kennung zur Identifizierung von Nachrichten zwischen
     * Raspi und Arduino
     * <p>
     * Der <b><code>token</code></b> dient zur Zuordnung einzelner Nachrichten
     * zwischen Arduino und Raspberry Pi.
     * </p>
     * <p>
     * <ul>
     * <li>Raspi wird immer vom Arduino getaktet.</li>
     * <li>Der Raspi protokolliert dann der erfolgreichen Erhalt der letzten Message vom
     * Arduino durch Mitgabe des tokens aus dieser Message und der Statusinformation SUCCESS</li>
     * <li>Der Arduino schickt dann in der Antwort den naechsten token mit den neuen Nachricht an den
     * Raspi...</li> 
     * </ul>
     * </p>
     */
    private long token = 0L;
    
    /**
     * DESTINATION_SIMULTAN_KEY = "destinationSimultanKey"
     */
    public final static String DESTINATION_SIMULTAN_KEY = "destinationSimultanKey";
    
    /**
     * DESTINATION_MA_KEY = "destinationMAKey" - Key zum Zugriff auf den Sollwert der Zielgroesse 
     * (Drehzahl Motor A)
     * <p>
     * Die Zielgroesse fuer die Drehzahl Motor A wird an der Oberflaeche als Anzahl Umdrehungen 
     * pro Zeiteinheit angegeben. Der Eingabewert wird in das Model uebertragen und finden sich 
     * unter dem Key DESTINATION_MA_KEY in der Map dataMap wider.
     * </p>
     */
    public final static String DESTINATION_MA_KEY = "destinationMAKey";
    
    /**
     * DESTINATION_MB_KEY = "destinationMBKey" - Key zum Zugriff auf den Sollwert der Zielgroesse 
     * (Drehzahl Motor B)
     * <p>
     * Die Zielgroesse fuer die Drehzahl Motor B wird an der Oberflaeche als Anzahl Umdrehungen 
     * pro Zeiteinheit angegeben. Der Eingabewert wird in das Model uebertragen und finden sich 
     * unter dem Key DESTINATION_MB_KEY in der Map dataMap wider.
     * </p>
     */
    public final static String DESTINATION_MB_KEY = "destinationMBKey";
    
    /**
     * OUTPUT_RELATION_KEY = "outputRelationKey" - Key zum Zugriff auf den Faktor zur Bestimmung
     * des Sollwertes aus der Sollwertvorgabe
     */
    public final static String OUTPUT_RELATION_KEY = "outputRelationKey";
    
    /**
     * totalMA[] - totale Impuls-Zaehler-Staende Motor A
     * <p>
     * totalMA[1] - aktueller Wert[k], totalMA[0] - historischer Wert[k-1] 
     * </p>
     */
    private long totalMA[] = { 0L, 0L };
    
    /**
     * controlMA[] - Reglerausgang zum Motor A
     * <p>
     * controlMA[0] - historischer Wert[k-1], wichtig zur Ermittlung des VZ
     * </p>
     */
    private BigDecimal controlMA[] = { BigDecimal.ZERO, BigDecimal.ZERO };
   
    /**
     * boolean isDestinationSimultan - Sollwerte Motor A/B werden simultan gehalten...
     */
    private boolean isDestinationSimultan = false;
    
    /**
     * valueDestinationMA - Lagesollwert als Zustandsgroesse Motor A
     * valueDestinationMA => numberDestinationMA
     */
    private BigDecimal valueDestinationMA = BigDecimal.ZERO;
    
    /**
     * long numberDestinationMA - Sollwert Motor A
     */
    private long numberDestinationMA = 0L;
    
    /**
     * long numberMA - Lageinformation Motor A...
     */
    private long numberMA = 0L;

    /**
     * totalMB[] - totale Impuls-Zaehler-Staende Motor B
     * <p>
     * totalMB[1] - aktueller Wert[k], totalMB[0] - historischer Wert[k-1] 
     * </p>
     */
    private long totalMB[] = { 0L, 0L };
    
    /**
     * controlMB[] - Reglerausgang zum Motor B
     * <p>
     * controlMB[0] - historischer Wert[k-1], wichtig zur Ermittlung des VZ
     * </p>
     */
    private BigDecimal controlMB[] = { BigDecimal.ZERO, BigDecimal.ZERO };
    
    /**
     * valueDestinationMB - Lagesollwert als Zustandsgroesse Motor B
     * valueDestinationMA => numberDestinationMA
     */
    private BigDecimal valueDestinationMB = BigDecimal.ZERO;
    
    /**
     * long numberDestinationMB - Sollwert Motor B
     */
    private long numberDestinationMB = 0L;
    
    /**
     * long numberMB - Lageinformation Motor B...
     */
    private long numberMB = 0L;
    
    /**
     * destinationMA - Zielgroesse Drehzahl Motor A (1/min), Eingabe durch ComboBox... 
     */
    private BigDecimal destinationMA = DESTINATION_VALUES[SELECTED_DESTINATION_INDEX];
    
    /**
     * destinationOutputMA - Umrechnung von destinationMA in die Ausgabe ueber OUTPUT_RELATION_VALUES...
     */
    private BigDecimal destinationOutputMA = destinationMA.divide(OUTPUT_RELATION_VALUES[SELECTED_OUTPUT_RELATION_INDEX], 
                                                                                         SCALE_INTERN, 
                                                                                         BigDecimal.ROUND_DOWN);
    
    /**
     * destinationMB - Zielgroesse Drehzahl Motor bB (1/min), Eingabe durch ComboBox... 
     */
    private BigDecimal destinationMB = DESTINATION_VALUES[SELECTED_DESTINATION_INDEX];
    
    /**
     * destinationOutputMB - Umrechnung von destinationMA in die Ausgabe ueber OUTPUT_RELATION_VALUES...
     */
    private BigDecimal destinationOutputMB = destinationMB.divide(OUTPUT_RELATION_VALUES[SELECTED_OUTPUT_RELATION_INDEX], 
                                                                                         SCALE_INTERN, 
                                                                                         BigDecimal.ROUND_DOWN);
    
    /**
     * outputRelation - Faktor zur Umsetzung der Sollwertvorgabe Drehzahl zur Ausgabe...
     */
    private BigDecimal outputRelation = OUTPUT_RELATION_VALUES[SELECTED_OUTPUT_RELATION_INDEX];
    
    /**
     * maxValueMA - Maximalwert des Sollwertes fuer
     * Motor A, Vorgabe durch die GUI
     * <p>
     * Bereich maxValueMA (Limit): -1.0 ... 0.0 ... +1.0
     * </p>
     */
    private BigDecimal maxValueMA = BigDecimal.ZERO;
    
    /**
     * maxValueMB - Maximalwert des Sollwertes fuer
     * Motor B, Vorgabe durch die GUI
     * <p>
     * Bereich maxValueMA (Limit): -1.0 ... 0.0 ... +1.0
     * </p>
     */
    private BigDecimal maxValueMB = BigDecimal.ZERO;
    
    /**
     * boolean isControlled - boolsche Kennung: Regelung ja/nein...
     */
    private boolean isControlled = false;
    
    /**
     * outputMA - Stellgroesse Motor A
     * <p>
     * Bereich outputMA: -1.0 ... 0.0 ... +1.0 
     * </p>
     */
    private BigDecimal outputMA;
    
    /**
     *  outputMB - Stellgroesse Motor B
     * <p>
     * Bereich outputMB: -1.0 ... 0.0 ... +1.0 
     * </p>
     */
    private BigDecimal outputMB;
    
    /**
     * SIZES_DIFF_VALUES - Anzahl der Messwerte zur Feststellung der 
     * Drehzahl Motor A/Motor B
     */
    public final static int SIZES_DIFF_VALUES = 20;
    
    /**
     * cycleTimeValues - Array mit den SIZES_DIFF_VALUES (=Anzahl) Taktzeiten...
     */
    private final BigDecimal[] cycleTimeValues = new BigDecimal[SIZES_DIFF_VALUES];
    
    /**
     * diffValuesMA - Array mit den SIZES_DIFF_VALUES (=Anzahl) Impulsen Motor A
     */
    private final long[] diffValuesMA = new long[SIZES_DIFF_VALUES]; 
    
    /**
     * diffValuesMB - Array mit den SIZES_DIFF_VALUES (=Anzahl) Impulsen Motor B
     */
    private final long[] diffValuesMB = new long[SIZES_DIFF_VALUES]; 
    
    /**
     * realValueMA - Messwert Drehzahl Motor A
     */
    private BigDecimal realValueMA;
    
    /**
     * realValueMB - Messwert Drehzahl Motor B
     */
    private BigDecimal realValueMB;

    /**
     * SCALE_OUTPUT = 3 - Genauigkeit (Anzahl der Nachkommastellen) der Ausgabe an den HAT
     */
    public final static int SCALE_OUTPUT = 3;
    
    /**
     * CIRCUMFERENCE - Anzahl der Impulse des Gebers pro Umdrehung
     * 
     * Aus der Anzahl der Impulse I pro Zeiteinheit T ergibt sich die
     * Umdrehungszahl U pro Minute zu:
     * 
     *   U = I * 1/CIRCUMFERENCE * 60/T
     *   U = (I/T) * (60/CIRCUMFERENCE) 
     */
    public final static int CIRCUMFERENCE = 6;
    
    /**
     * SCALE_INTERN = 6 - Genauigkeit interner Daten.
     */
    public final static int SCALE_INTERN = 6;
    
    /**
     * RPM_CONST - Parameter, ergibt sich aus (Impulsanzahl pro Umdrehung)/(60L) zur weiteren verwendung...
     */
    public final static BigDecimal RPM_CONST = BigDecimal.valueOf(CIRCUMFERENCE).divide(BigDecimal.valueOf(60L), SCALE_INTERN, BigDecimal.ROUND_DOWN);

    /**
     * positionController - Referenz auf den Regler...
     */
    private final PositionController positionController = new PositionController(CIRCUMFERENCE);
    
    /**
     * Pull-Up/Pull-Down-Einstellung...
     * <p>
     * Hier Voreinstellung auf PinPullResistance.OFF, da Pull-Down-Widerstaende 
     * durch die Hardware bereitgestellt werden...
     * </p>
     * <p>
     * Hier Einstellung Kein Pull-Down/Pull-Up durch den Raspi...
     * </p>
     */
    private final static PinPullResistance PIN_PULL_RESISTANCE = PinPullResistance.OFF;
    
    /**
     * GPIO_CYCLE_PIN - der Pin wird durch den ArduinoI2C UNO getaktet...
     */
    private final static Pin GPIO_CYCLE_PIN = RaspiPin.GPIO_04;    // GPIO23 (GPIO_GEN4), Board-Nr=16
    
    /**
     * GPIO_CYCLE_PIN_NAME - Name des GPIO_CYCLE_PIN
     */
    private final static String GPIO_CYCLE_PIN_NAME = GPIO_CYCLE_PIN.getName();
    
    /**
     * GPIO_PINS - ...die folgenden (Ausgabe-) Pins werden angesprochen...
     * <p>
     * Es handel sich um Output-Pins!
     * </p>
     */
    private final static Pin[] GPIO_PINS = 
    {
        // Beispielsweise: RaspiPin.GPIO_00    // GPIO 17, Board-Nr=11
    };
    
    /**
     * PIN_NAMES - String-Array mit den Namen der Raspi Ausgabe-Pin's.
     * Das Array wird aus dem Array GPIO_PINS[] befuellt.
     */
    public final static String[] PIN_NAMES = new String[GPIO_PINS.length];
    
    static 
    {
        // Befuellen des Arrays PIN_NAMES[] aus GPIO_PINS[]...
        for(int index = 0; index < GPIO_PINS.length; index++)
        {
            PIN_NAMES[index] = GPIO_PINS[index].getName();
        }
    }
  
    /**
     * gpioPinDigitalInputCyclePin haelt das GpioPinDigitalInput-Objekte.
     * <p>
     * Es handelt sich um ein Input-Objekt. Daher wird die Referenz direkt 
     * abgelegt. Bei mehreren Input-Objekten wuerde es sich anbieten, eine
     * Map anzulegen...
     * </p>
     * <p>
     * Ueber die gpioPinDigitalInputCyclePin-Referenz erfolgt die zyklische
     * Beauftragung der Regelung. Der Zyklus wird dabei vom Arduino vorgegeben. 
     * </p>
     */
    private final GpioPinDigitalInput gpioPinDigitalInputCyclePin;

    /**
     * gpioPinOutputMap nimmt die GpioPinDigitalOutput-Objekte auf, 
     * Key ist dabei jeweils der Pin_Name, z.B. "GPIO 21"...
     * <p>
     * Verwendung: Unter dem Key 'Name des GPIO' wird die Referenz 
     * auf den Pin abgelegt. 
     * </p>
     * <p>
     * Diese Map wird im weiteren nicht verwendet, steht nur als Muster
     * bereit...
     * </p>
     */
    private final java.util.TreeMap<String, GpioPinDigitalOutput> gpioPinOutputMap = new java.util.TreeMap<>();
    
    /**
     * NAME_START_BUTTON = "startButton"
     */
    public static final String NAME_START_BUTTON = "startButton";

    /**
     * NAME_STOP_BUTTON = "stopButton"
     */
    public static final String NAME_STOP_BUTTON = "stopButton";
    
    /**
     * NAME_RESET_BUTTON = "resetButton"
     */
    public static final String NAME_RESET_BUTTON = "resetButton";
    
    /**
     * NAME_END_BUTTON = "endButton"
     */
    public final static String NAME_END_BUTTON = "endButton";
    
    /**
     * dataMap - nimmt die Eingaben auf...
     * <p>
     * Ablage key => Eingabe-Object
     * </p>
     */
    private final java.util.TreeMap<String, Object>  dataMap = new java.util.TreeMap<>();

    /**
     * DATA_KEY = "dataKey" - Key unter dem die Data in der dataMap abgelegt werden...
     * <p>
     * Data umfasst die Zustandsgroessen, die in der View angezeigt werden.
     * </p>
     */
    public final static String DATA_KEY = "dataKey";
    
    /**
     * MAX_VALUE_MA_KEY = "maxValueMAKey"
     * <p>
     * MAX_VALUE_MA_KEY referenziert die Zustandsgroesse maxValueMA
     * </p>
     */
    public final static String MAX_VALUE_MA_KEY = "maxValueMAKey";
    
    /**
     * MAX_VALUE_MB_KEY = "maxValueMBKey"
     * <p>
     * MAX_VALUE_MB_KEY referenziert die Zustandsgroesse maxValueMB
     * </p>
     */
    public final static String MAX_VALUE_MB_KEY = "maxValueMBKey";
    
    /**
     * OUTPUT_MA_KEY = "outputMAKey"
     */
    public final static String OUTPUT_MA_KEY = "outputMAKey";
    
    /**
     * OUTPUT_MB_KEY = "outputMBKey"
     */
    public final static String OUTPUT_MB_KEY = "outputMBKey";
    
    /**
     * CONTROL_KEY = "controlKey" - Boolscher Schalter 'Mit Regelung'
     */
    public final static String CONTROL_KEY = "controlKey";

    /**
     * ENHANCEMENT_KEY = "enhancementKey" - Combobox mit den Reglerverstaerkungen...
     */
    public final static String ENHANCEMENT_KEY = "enhancementKey";
    
    /**
     * GUI_STATUS_KEY = "guiStatusKey" - Im GuiStatus wird abgelegt im welchem
     * "Bedienungszustand" die Gui ist.
     */
    public final static String GUI_STATUS_KEY = "guiStatusKey";
    
    /**
     * DATA_KEYS[] - Array mit den Keys zur Ablage in der dataMap...
     */
    private final static String[] DATA_KEYS = 
    {
        DATA_KEY,
        DESTINATION_SIMULTAN_KEY,
        DESTINATION_MA_KEY,
        DESTINATION_MB_KEY,
        OUTPUT_RELATION_KEY,
        MAX_VALUE_MA_KEY,
        MAX_VALUE_MB_KEY,
        OUTPUT_MA_KEY,
        OUTPUT_MB_KEY,
        CONTROL_KEY,
        ENHANCEMENT_KEY,
        GUI_STATUS_KEY
    };
    
    /**
     * ???
     */
    public final static int MX_VALUE_SCALE = 2;
    
    /**
     * SCALE_DESTINATION = 3 - Genauigkeit der Sollwertvorgabe (3 Nachkommastellen)
     */
    public final static int SCALE_DESTINATION = 2;
    
    /**
     * DESTINATION_VALUES - Vorgaben fuer die ComboBoxen DESTINATION_MA_KEY, DESTINATION_MB_KEY
     */
    public final static BigDecimal[] DESTINATION_VALUES = new BigDecimal[]
    {
        BigDecimal.valueOf(120.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(110.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(100.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(90.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(80.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(70.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(60.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(50.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(40.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(30.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(20.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(10.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(-10.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(-20.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(-30.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-40.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-50.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-60.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-70.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-80.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-90.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-100.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-110.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(-120.0).setScale(SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP)        
    };
    
    /**
     * Index zur Auswahl der Sollwert-Selektion...
     */
    public final static int SELECTED_DESTINATION_INDEX = 12;
   
    /**
     * 
     */
    public final static int SCALE_OUTPUT_RELATION = 2;
    
    /**
     * 
     */
    public final static BigDecimal[] OUTPUT_RELATION_VALUES = new BigDecimal[]
    {
        BigDecimal.valueOf(200.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(190.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(180.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(170.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(160.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(150.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(140.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(130.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(120.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(110.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(100.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(90.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(80.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(70.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(60.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN ),
        BigDecimal.valueOf(50.00).setScale(SCALE_OUTPUT_RELATION, BigDecimal.ROUND_DOWN )
    };
     
    /**
     * 
     */
    public final static int SELECTED_OUTPUT_RELATION_INDEX = 9;
    
    /**
     * SCALE_MX_MAX_VALUE = 2 - Genauigkeit der Limit-Vorgabe Mx_Max_Value (2 Nachkommastellen)
     */
    public final static int SCALE_MX_MAX_VALUE = 2;
    
    /**
     * MX_MAX_VALUES - Vorgaben fuer die ComboBoxen MAX_VALUE_MA_KEY, MAX_VALUE_MB_KEY
     */
    public final static BigDecimal[] MX_MAX_VALUES = new BigDecimal[]
    {
        BigDecimal.valueOf(1.0).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.9).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.8).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.7).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.6).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.5).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.4).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.3).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.2).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.1).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP),                    
        BigDecimal.valueOf(0.0).setScale(SCALE_MX_MAX_VALUE, BigDecimal.ROUND_HALF_UP)                   
    };
    
    /**
     * Index zur Vor-Auswahl der Selektion...
     */
    public final static int SELECTED_MX_MAX_VALUES_INDEX = 10;

    
    /**
     * Genauigkeit (Anzahl der Nachkommastellen) in der Verstaerkungsangabe
     */
    public final static int  SCALE_ENHANCEMENT = 4;
    
    /**
     * ENHANCEMENTS - Array mit den Verstaerkungswerten des Reglers (P-Anteil) 
     * zur Auswahl in der Combobox...
     */
    public final static BigDecimal[] ENHANCEMENTS = new BigDecimal[]
    {
        BigDecimal.valueOf(0.000).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.002).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.005).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.010).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.020).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.050).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.100).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.200).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.300).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(0.500).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(1.000).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(2.000).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(5.000).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP),
        BigDecimal.valueOf(10.00).setScale(SCALE_ENHANCEMENT, BigDecimal.ROUND_HALF_UP)
    };
    
    /**
     * Index zur Auswahl der Selektion...
     */
    public final static int SELECTED_ENHANCEMENTS_INDEX = 0;
    
    /**
     * support - Referenz auf den PropertyChangeSupport...
     */
    private final PropertyChangeSupport support = new PropertyChangeSupport(this);
    
    /**
     * Default-Konstruktor 
     */
    public Model()
    {
        // Zuallererst: Wo erfolgt der Lauf, auf einem Raspi?
        final String os_name = System.getProperty("os.name").toLowerCase();
        final String os_arch = System.getProperty("os.arch").toLowerCase();
        logger.debug("Betriebssytem: " + os_name + " " + os_arch);
        // Kennung isRaspi setzen...
        this.isRaspi = OS_NAME_RASPI.equals(os_name) && OS_ARCH_RASPI.equals(os_arch);
        
        // ...den gpioController anlegen...
        this.gpioController = isRaspi? GpioFactory.getInstance() : null;
        
        // *** Befuellen der dataMap... ***
        // Die dataMap muss mit allen Key-Eintraegen befuellt werden, sonst 
        // ist setProperty(String key, Object newValue) unwirksam!
        for (String key: Model.DATA_KEYS)
        {
            this.dataMap.put(key, null);
        }
        
        {
            ArduinoI2C arduinoLoc = null;
            MotorDriverHAT motorDriverHATLoc = null;
            try
            {
                // i2cBus wird nicht in Instanzvariable abgelegt, da ueber I2CFactory erreichbar!
                final I2CBus i2cBus = isRaspi? I2CFactory.getInstance(I2CBus.BUS_1) : null;
                // Verbindung zum Arduino instanziieren...
                arduinoLoc = isRaspi? (new ArduinoI2C((i2cBus != null)? i2cBus.getDevice(ARDUINO_ADDRESS) : null)) 
                                     : null;
                
                // MotorDriverHAT instanziieren (auf der Adresse und mit der Frequenz)...
                motorDriverHATLoc = isRaspi? (new MotorDriverHAT(((i2cBus != null)? i2cBus.getDevice(MD_HAT_ADDRESS) : null),
                                                                 MD_HAT_FREQUENCY))
                                            : null;
            }
            catch (UnsupportedBusNumberException | IOException exception)
            {
                logger.error(exception.toString(), exception);
                System.err.println(exception.toString());
                System.exit(0);
            }
            this.arduinoI2C = arduinoLoc;
            // Status der Kommunikation auf NOP und token auf 0L...
            this.i2cStatus = ArduinoI2C.Status.NOP;
            this.token = 0L;
            
            this.motorDriverHAT = motorDriverHATLoc;
        }
        
        {
            //////////////////////////////////////////////////////////////////////////
            // Input-Pins einstellen (plus Eventhandling)...
            if (isRaspi)
            {
                // *** Zugriff auf die Input-Pin nur wenn Lauf auf dem Raspi... ***
                GpioPinDigitalInput gpioInputPin = this.gpioController.provisionDigitalInputPin(Model.GPIO_CYCLE_PIN, 
                                                                                                Model.GPIO_CYCLE_PIN_NAME, 
                                                                                                Model.PIN_PULL_RESISTANCE);
                // Event-Handler (Listener) instanziieren...
                gpioInputPin.addListener(new GpioPinListenerDigital() 
                {
                    /**
                     * Event-Verarbeitung angestossen durch den  ArduinoI2C-Uno...
                     * <p>
                     * Der Handler wird in einem festen Takt durch den Arduino beauftragt.
                     * Innerhalb des Handlers ist die Kommunikation mit dem Arduino und die
                     * Berechnung der Regelalgorithmen vorzunehmen.
                     * </p>
                     */
                    @Override
                    public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event)
                    {
                        final GpioPin gpioPin = event.getPin();
                        final String pinName = gpioPin.getName();
                        final PinEdge pinEdge = event.getEdge();
                        // Reaktion erfolgt an der steigenden Flanke...
                        if (PinEdge.RISING == pinEdge)
                        {
                            //////////////////////////////////////////////////////////////////////////
                            // Die Taktung durch den ArduinoI2C hat einen Referenzpunkt 
                            // erreicht.
                            // Variable now dient zur zeitlichen Einordnung des Ereignisses...
                            // Jetzt werden die Kenngroesse der Taktung ermittelt:
                            // - now: der jetzige Zeitpunkt, 
                            // -      die Zeitdauer ergibt sich dann
                            //        durch Differenzbildung zu Model.this.past...
                            // now wird im weiteren Verlauf im Zustand Model.this.past 
                            // abgelegt. 
                            final Instant now = Instant.now();
                            
                            // Model.this.past: Zeitpunkt der letzten Taktung...
                            if (Model.this.past == null)
                            {
                                // Erste Beauftragung: Model.this.past = null...
                                Model.this.past = now;
                            }
                            // Model.this.cycleTime: Taktzeit aus der Differenz now - past.
                            // Ablage der aktuell gemessenen Taktzeit in der Zustandsgroesse cycleTime...
                            Model.this.cycleTime = toBigDecimalSeconds(Duration.between(Model.this.past, now), 
                                                                       Model.SCALE_INTERN); 
                            
                            // ...und Ablage des aktuelle Zeitpunktes...
                            Model.this.past = now;
                            //////////////////////////////////////////////////////////////////////////
                            
                            final Object statusObject = Model.this.dataMap.get(Model.GUI_STATUS_KEY);
                            final Model.GuiStatus guiStatus = (statusObject instanceof Model.GuiStatus)? (Model.GuiStatus) statusObject 
                                                                                                        : null;
                            final boolean isStarted = (guiStatus != null) && (guiStatus == GuiStatus.START); 
                            
                            //////////////////////////////////////////////////////////////////////////
                            // Berechnung des Zuwachses an Lage Motor A und Motor B:
                            //
                            final BigDecimal rpm_const_cycleTime = Model.RPM_CONST.multiply(Model.this.cycleTime);

                            // deltaA - Zuwachs Motor A (nur wenn Status START ist, sonst Zuwachs gleich ZERO)...
                            final BigDecimal deltaA = isStarted? Model.this.destinationMA.multiply(rpm_const_cycleTime) 
                                                               : BigDecimal.ZERO.setScale(SCALE_INTERN);
                            Model.this.valueDestinationMA = Model.this.valueDestinationMA.add(deltaA);
                            Model.this.numberDestinationMA =  Model.this.valueDestinationMA.longValue();
                            
                            // deltaB - Zuwachs Motor B (nur wenn Status START ist, sonst Zuwachs gleich ZERO)...
                            final BigDecimal deltaB = isStarted? Model.this.destinationMB.multiply(rpm_const_cycleTime)
                                                               : BigDecimal.ZERO.setScale(SCALE_INTERN);
                            Model.this.valueDestinationMB = Model.this.valueDestinationMB.add(deltaB); 
                            Model.this.numberDestinationMB = Model.this.valueDestinationMB.longValue();
                            
                            if (Model.this.dataMap.containsKey(Model.DATA_KEY))
                            {
                                // Die Beauftragung durch Inkrementierung des Zaehlers 
                                // Model.this.counter 'dokumentieren'...
                                // Die dataMap haelt die Daten zur Anzeige in der View, hier DATA_KEY => Data(),
                                // und Data() beinhaltet den aktuellen counter (und weiteres...)
                                
                                // Model.this.counter inkrementieren oder zu 1L setzen...
                                Model.this.counter = ((Model.this.counter + 1L) > 0L)? (Model.this.counter + 1L) : 1L;  
                                
                                final Data data = new Data(Model.this.counter, 
                                                           Model.this.cycleTime,
                                                           Model.this.token,
                                                           Model.this.numberDestinationMA,
                                                           Model.this.numberDestinationMB,
                                                           Model.this.numberMA,
                                                           Model.this.numberMB,
                                                           Model.this.outputMA,
                                                           Model.this.outputMB,
                                                           Model.this.realValueMA,
                                                           Model.this.realValueMB); 
                                setProperty(Model.DATA_KEY, data);
                            }
                            else
                            {
                                
                            }
                            label:
                            {
                                //////////////////////////////////////////////////////////////////////////////
                                // Es folgt die Beauftragung der Kommunikation mit dem Arduino...
                                // 1.) Wenn statusI2C == NOP, dann keine Beauftragung...
                                //
                                if (ArduinoI2C.Status.NOP == Model.this.i2cStatus)
                                {
                                    break label;
                                }
                                if (ArduinoI2C.Status.INITIAL == Model.this.i2cStatus)
                                {
                                    // INITIAL wurde durch den Start-Button gesetzt.
                                    // 1.) Als token 0L einstellen...
                                    Model.this.token = 0L;
                                    // 2.) Kommunikation beginnen...
                                }
                                try
                                {
                                    //////////////////////////////////////////////////////////////////////////
                                    // tokenToArduino: Lokale Variable, die vier unteren Bytes 
                                    //                 der long-Instanzvariable this.token...
                                    final long tokenToArduino = (Model.this.token & 0xffffffff);
                                    Model.this.arduinoI2C.write(token, Model.this.i2cStatus);
                                    logger.debug("i2c-Bus: " + tokenToArduino + " gesendet...");
                                    
                                    ArduinoI2C.DataRequest request = Model.this.arduinoI2C.read();
                                    logger.debug("i2c-Bus: " + request.toString() + " gelesen...");
                                    final long tokenFromArduino = request.getToken();
                                    final ArduinoI2C.Status statusFromArduino = request.getStatus();
                                    // valueFromArduino beinhaltet die 4 Byte-Variante der Daten vom Arduino...
                                    final int valueFromArduino = request.getValue();
                                    // numberMAFromArduino: Anzahl Impulse Motor A...
                                    final int numberMAFromArduino = request.getNumberMA();
                                    // numberMBFromArduino: Anzahl Impulse Motor B...
                                    final int numberMBFromArduino = request.getNumberMB();
                                    // Der Arduino wird den token inkrementieren und als
                                    // neuen Token zurueckschicken. Wenn die Differenz
                                    // gleich 1L ist, kann man davon ausgehen, dass auf
                                    // dem Arduino alles korrekt laeuft...
                                    if ((tokenFromArduino - tokenToArduino == 1L) 
                                     && (ArduinoI2C.Status.SUCCESS == statusFromArduino))
                                    {
                                        Model.this.i2cStatus = ArduinoI2C.Status.SUCCESS;
                                        Model.this.token = (tokenFromArduino & 0xffffffff);
                                        
                                        // "Umschiften..."
                                        Model.this.totalMA[0] = Model.this.totalMA[1];
                                        Model.this.totalMA[1] = numberMAFromArduino;
                                        Model.this.controlMA[0] = Model.this.controlMA[1];
                                        // diffMA => Zuwachs Motor A:
                                        final long diffMA = Model.this.totalMA[1] - Model.this.totalMA[0];
                                                
                                        Model.this.totalMB[0] = Model.this.totalMB[1];
                                        Model.this.totalMB[1] = numberMBFromArduino;
                                        Model.this.controlMB[0] = Model.this.controlMB[1];
                                        // diffMB => Zuwachs Motor B:
                                        final long diffMB = Model.this.totalMB[1] - Model.this.totalMB[0];
                                        
                                        final int signumMA = Model.this.controlMA[0].signum();
                                        final int signumMB = Model.this.controlMB[0].signum();
                                        
                                        // numberMA/numberMB - absolute Lage der Motoren in Impulse:
                                        Model.this.numberMA += signumMA * diffMA;
                                        Model.this.numberMB += signumMB * diffMB;
                                        
                                        //////////////////////////////////////////////////////////////////
                                        // Berechnung der gemittelten Drehzahlen Motor A und Motor B
                                        // und Ablage der Werte in realValueMA und realValueMB...
                                        calculateRealValues(Model.this.cycleTime, diffMA, diffMB);
                                        //////////////////////////////////////////////////////////////////
                                        
                                        final String msg = new StringBuilder().append("Sollwerte: ")
                                                                              .append(Model.this.numberDestinationMA)
                                                                              .append(" ")
                                                                              .append(Model.this.numberDestinationMB)
                                                                              .append(", Istwerte: ")
                                                                              .append(Model.this.numberMA)
                                                                              .append(" ")
                                                                              .append(Model.this.numberMB)
                                                                              .append(", Limitierungen: ")
                                                                              .append(Model.this.maxValueMA)
                                                                              .append(" ")
                                                                              .append(Model.this.maxValueMB)
                                                                              .toString();
                                        
                                        logger.debug(msg);
                                        
                                        //
                                        // Stellgroessen ohne Reglereingriff:
                                        // - destinationOutputMA (BigDecimal)
                                        // - destinationOutputMB (BigDecimal)
                                        // Sollwerte der Lage
                                        // - numberDestinationMA (long)
                                        // - numberDestinationMB (long)
                                        // Istwerte der Lage
                                        // - numberMA (long)
                                        // - numberMB (long)
                                        
                                        
                                        final PositionController.Output output = Model.this.getPositionController().doControl(Model.this.numberDestinationMA, Model.this.numberMA,
                                                                                                                              Model.this.numberDestinationMB, Model.this.numberMB,
                                                                                                                              Model.this.destinationOutputMA, 
                                                                                                                              Model.this.destinationOutputMB,
                                                                                                                              Model.this.maxValueMA, 
                                                                                                                              Model.this.maxValueMB);
                                        
                                        logger.debug("doControl(): " + output.toString());
                                        
                                        Model.this.outputMA = Model.this.isControlled? output.getOutputMA() : Model.this.destinationOutputMA.setScale(SCALE_OUTPUT, BigDecimal.ROUND_FLOOR);
                                        Model.this.outputMB = Model.this.isControlled? output.getOutputMB() : Model.this.destinationOutputMB.setScale(SCALE_OUTPUT, BigDecimal.ROUND_FLOOR);
                                        
                                        // outputMA und outputMB merken...
                                        Model.this.controlMA[1] = Model.this.outputMA;
                                        Model.this.controlMB[1] = Model.this.outputMB;
                                        
                                        //
                                        final float speedMA = ((Model.this.outputMA != null)? Model.this.outputMA.floatValue() : 0.0F);
                                        final float speedMB = ((Model.this.outputMB != null)? Model.this.outputMB.floatValue() : 0.0F);
                                        
                                        Model.this.motorDriverHAT.setPwmMA(speedMA);
                                        Model.this.motorDriverHAT.setPwmMB(speedMB);
                                        
                                    }
                                    else
                                    {
                                        Model.this.i2cStatus = ArduinoI2C.Status.ERROR;
                                        
                                        Model.this.motorDriverHAT.setPwmMA(0.0F);
                                        Model.this.motorDriverHAT.setPwmMB(0.0F);
                                    }
                                } 
                                catch (IOException exception)
                                {
                                    logger.error(exception.toString(), exception);
                                    System.err.println(exception.toString());
                                }
                            }
                            //
                            //////////////////////////////////////////////////////////////////////////
                            
                            {
                                //////////////////////////////////////////////////////////////////////////////////////////////////
                                // Testausgabe: Dauer der Bearbeitung von handleGpioPinDigitalStateChangeEvent() von 0.001 ... 0.006s
                                // final BigDecimal duration = toBigDecimalSeconds(Duration.between(Model.this.past, Instant.now()), 
                                //                                                 Model.SCALE_CYCLE_TIME); 
                                // Evtl. Log-Ausgabe...
                                // logger.debug("Dauer handleGpioPinDigitalStateChangeEvent() in s: " + duration);
                                //////////////////////////////////////////////////////////////////////////////////////////////////
                            }
                        } // end() - (PinEdge.RISING == pinEdge).
                    }
                    
                    /**
                     * toBigDecimalSeconds(Duration duration) - liefert die Anzahl der Sekunden
                     * <p>
                     * Vgl. toBigDecimalSeconds() aus Duration in Java 11.
                     * </p<
                     * @param duration
                     * @return
                     */
                    private BigDecimal toBigDecimalSeconds(Duration duration, int scale)
                    {
                        Objects.requireNonNull(duration, "duration must not be null!");
                        final BigDecimal result = BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9)).setScale(scale,  BigDecimal.ROUND_HALF_UP);
                        return (result.compareTo(BigDecimal.ONE.movePointLeft(scale)) < 0)? BigDecimal.ZERO : result;   
                    }
                    
                    
                    /**
                     * calculateRealValues(BigDecimal cycleTime, long diffValueMA, long diffValueMB) - Ermittlung
                     * der gemittelten Drehzahlen (in 1/min) Motor A und Motor B...
                     */
                    private void calculateRealValues(BigDecimal cycleTime, long diffValueMA, long diffValueMB)
                    {
                        // folgende Partialsummen...
                        BigDecimal summCycleTime = cycleTime.setScale(SCALE_INTERN, BigDecimal.ROUND_HALF_UP);
                        long summValuesMA = diffValueMA;
                        long summValuesMB = diffValueMB;
                        
                        // Einsortieren an Position index = 0, 
                        // Verschieben von index nach index+1
                        // Verwerfen von index = Model.SIZES_DIFF_VALUES
                        
                        int index = Model.SIZES_DIFF_VALUES-1;
                        while (index > 0)
                        {
                            Model.this.cycleTimeValues[index] = Model.this.cycleTimeValues[index-1];
                            summCycleTime = summCycleTime.add((Model.this.cycleTimeValues[index] != null)? Model.this.cycleTimeValues[index]
                                                                                                         : BigDecimal.ZERO);
                            
                            Model.this.diffValuesMA[index] = Model.this.diffValuesMA[index-1];
                            summValuesMA += Model.this.diffValuesMA[index];
                            
                            Model.this.diffValuesMB[index] = Model.this.diffValuesMB[index-1];
                            summValuesMB += Model.this.diffValuesMB[index];
                            
                            index--;
                        }
                        // Jeweils Element (index = 0) in das Array setzen und addieren...
                        Model.this.cycleTimeValues[0] = cycleTime.setScale(SCALE_INTERN, BigDecimal.ROUND_HALF_UP);
                        summCycleTime = summCycleTime.add(Model.this.cycleTimeValues[0]);
                        
                        Model.this.diffValuesMA[0] = diffValueMA;
                        summValuesMA += Model.this.diffValuesMA[0];
                        
                        Model.this.diffValuesMB[0] = diffValueMB;
                        summValuesMB += Model.this.diffValuesMB[0];
                        
                        // Jetzt ist bestimmt worden:
                        // 1.) Zeitdauer: summCycleTime
                        // 2.) Impulssumme A: summValuesMA
                        // 3.) Impulssumme B: summValuesMB
                        
                        // Berechnung:
                        // realValue = (Anzahl Impulse) *  (60/Impulse pro Umdrehung) / Zeitdauer
                        // mit: RPM_CONST = BigDecimal.valueOf(CIRCUMFERENCE).divide(BigDecimal.valueOf(60L), SCALE_INTERN, BigDecimal.ROUND_DOWN);
                        // folgt:
                        // realValue = {Anzahl Impulse} / {Zeitdauer * RPM_CONST}
                        
                        final BigDecimal divisor = summCycleTime.multiply(Model.RPM_CONST);
                        
                        Model.this.realValueMA = BigDecimal.valueOf(summValuesMA).divide(divisor, SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP);
                        Model.this.realValueMB = BigDecimal.valueOf(summValuesMB).divide(divisor, SCALE_DESTINATION, BigDecimal.ROUND_HALF_UP);
                    }
                });
                this.gpioPinDigitalInputCyclePin = gpioInputPin;
                // Ablage eines "leeren (Default-)" Data-Objektes in der dataMap...
                // Dem Key Model.DATA_KEY wird beispielsweise das Value Long.valueOf(0L) zugeordnet.
                setProperty(Model.DATA_KEY, new Data());
                logger.debug(Model.DATA_KEY + " in dataMap gesetzt.");                
            }
            else
            {
                this.gpioPinDigitalInputCyclePin = null;
                setProperty(Model.DATA_KEY, new Data());                
                logger.debug(Model.DATA_KEY + " in dataMap mit value=null aufgenommen.");
            }
            //////////////////////////////////////////////////////////////////////////
        }
        
        //////////////////////////////////////////////////////////////////////////
        // Output-pins beruecksichtigen...
        // Wenn Output, dann wird jeder Pin entsprechend konfiguriert
        // und ein Boolsche Wert (als Datenhaltung) zugeordnet...
        for (Pin pin: Model.GPIO_PINS)
        {
            final String key = pin.getName();
            this.dataMap.put(key, Boolean.FALSE);
            logger.debug(key + " in dataMap aufgenommen.");
            if (isRaspi)
            {
                // Zugriff auf die Pin nur wenn Lauf auf dem Raspi...
                GpioPinDigitalOutput gpioPin = this.gpioController.provisionDigitalOutputPin(pin, key, PinState.LOW);
                gpioPin.setShutdownOptions(true, PinState.LOW, PinPullResistance.OFF);
                this.gpioPinOutputMap.put(key, gpioPin);
            } 
            else
            {
                // Der Lauf erfolgt nicht auf dem Raspi...
                this.gpioPinOutputMap.put(key, null);
            }
        }
        //////////////////////////////////////////////////////////////////////////
        
        // Einige Daten initial setzen...
        setProperty(DESTINATION_SIMULTAN_KEY, Boolean.FALSE);
        setProperty(CONTROL_KEY, Boolean.FALSE);
        setProperty(GUI_STATUS_KEY, GuiStatus.INIT);
    }
     
    /**
     * 
     * @param listener
     */
    public void addPropertyChangeListener(PropertyChangeListener listener)
    {
        this.support.addPropertyChangeListener(listener);
    }

    /**
     * 
     * @param listener
     */
    public void removePropertyChangeListener(PropertyChangeListener listener)
    {
        this.support.removePropertyChangeListener(listener);
    }

    /**
     * setProperty(String key, Object newValue) - Die View wird informiert...
     * <p>
     * Die Beauftragung erfolgt z.B. durch den controller, dort model.setProperty(...).
     * Die Zustandsaenderung wird damit dem Model bekanntgegeben und die View ueber
     * support.firePropertyChange() informiert...
     * </p>
     * @param key
     * @param newValue
     */
    public void setProperty(String key, Object newValue)
    {
        if (this.dataMap.containsKey(key))
        {
            Object oldValue = this.dataMap.get(key); 
            this.dataMap.put(key, newValue);
            
            if (Model.DESTINATION_SIMULTAN_KEY.equals(key))
            {
                if (newValue instanceof Boolean)
                {
                    this.isDestinationSimultan = Boolean.TRUE.equals(newValue);
                    
                    logger.debug("isDestinationSimultan=" + this.isDestinationSimultan);
                }
            }
            
            if (Model.DESTINATION_MA_KEY.equals(key))
            {
                if (newValue instanceof BigDecimal)
                {
                    this.destinationMA = (BigDecimal) newValue;
                    this.destinationOutputMA = destinationMA.divide(this.outputRelation,
                                                                    SCALE_INTERN, 
                                                                    BigDecimal.ROUND_DOWN);
                    logger.debug("destinationMA=" + newValue);
                    logger.debug("destinationOutputMA=" + this.destinationOutputMA);
                }
            }
            
            if (Model.DESTINATION_MB_KEY.equals(key))
            {
                if (newValue instanceof BigDecimal)
                {
                    this.destinationMB = (BigDecimal) newValue;
                    this.destinationOutputMB = destinationMB.divide(this.outputRelation,
                                                                    SCALE_INTERN, 
                                                                    BigDecimal.ROUND_DOWN);
                    logger.debug("destinationMB=" + newValue);
                    logger.debug("destinationOutputMB=" + this.destinationOutputMB);
                }
            }
            
            if (Model.OUTPUT_RELATION_KEY.equals(key))
            {
                if (newValue instanceof BigDecimal)
                {
                    this.outputRelation = (BigDecimal) newValue;
                    this.destinationOutputMA = destinationMA.divide(this.outputRelation,
                                                                    SCALE_INTERN, 
                                                                    BigDecimal.ROUND_DOWN);
                    this.destinationOutputMB = destinationMB.divide(this.outputRelation,
                                                                    SCALE_INTERN, 
                                                                    BigDecimal.ROUND_DOWN);
                    logger.debug("outputRelation=" + newValue);
                    logger.debug("destinationOutputMA=" + this.destinationOutputMA 
                              + " destinationOutputMB=" + this.destinationOutputMB);
                }
            }
            
            if (Model.MAX_VALUE_MA_KEY.equals(key))
            {
                if (newValue instanceof BigDecimal)
                {
                    this.maxValueMA = (BigDecimal) newValue;
                    
                    logger.debug("maxValueMA=" + this.maxValueMA.toString());
                }    
            }
            
            if (Model.MAX_VALUE_MB_KEY.equals(key))
            {
                if (newValue instanceof BigDecimal)
                {
                    this.maxValueMB = (BigDecimal) newValue;
                    
                    logger.debug("maxValueMB=" + this.maxValueMB.toString());
                }    
            }
            
            if (Model.CONTROL_KEY.equals(key))
            {
                if (newValue instanceof Boolean)
                {
                    this.isControlled = Boolean.TRUE.equals(newValue);
                    
                    logger.debug("isControlled=" + this.isControlled);
                }
            }
            
            if (Model.ENHANCEMENT_KEY.equals(key))
            {
                if (newValue instanceof BigDecimal)
                {
                    // Die Verstaerkung (enhancement) findet sich nicht im Model,
                    // sondern im PositionController, daher Zugriff ueber 'Delegate'...
                    setEnhancement((BigDecimal) newValue);
                    
                    logger.debug("enhancement=" + getEnhancement().toString());
                }    
            }
            
            ////////////////////////////////////////////////////////////////////////
            // Evtl. Kein Logging an dieser Stelle...
            // if (oldValue == null || newValue == null || !oldValue.equals(newValue))
            // {
            //     logger.debug(key + ": " + oldValue + " => " + newValue);
            // }
            ////////////////////////////////////////////////////////////////////////
            
            support.firePropertyChange(key, oldValue, newValue);
        }
    }
    
    /**
     * 
     * @param speed
     * @throws IOException 
     */
    public void setPwmMA(float speed) throws IOException
    {
        if (this.motorDriverHAT != null)
        {
            this.motorDriverHAT.setPwmMA(speed);
        }
        else
        {
            logger.error("Fehler setPwmMA()!");   
        }
    }
    
    /**
     * 
     * @param speed
     * @throws IOException 
     */
    public void setPwmMB(float speed) throws IOException
    {
        if (this.motorDriverHAT != null)
        {
            this.motorDriverHAT.setPwmMB(speed);
        }
        else
        {
            logger.error("Fehler setPwmMB()!");   
        }
    }

    /**
     * 
     * @return
     */
    public PositionController getPositionController()
    {
        return this.positionController;
    }
    
    /**
     * setEnhancement(BigDecimal enhancement) - Delegate...
     * @param enhancement 
     */
    public void setEnhancement(BigDecimal enhancement)
    {
        this.positionController.setEnhancement(enhancement);
    }
    
    /**
     * getEnhancement() - Delegate...
     * @return enhancement (Reglerverstaerkung) des PositionController
     */
    public BigDecimal getEnhancement()
    {
        return this.positionController.getEnhancement();
    }
    
    /**
     * doStart() - Methode wird beim Start-Button beauftragt 
     * <p>
     * In der doStart()-Methode sind die Einstellungen vorzunehmen, damit
     * die Kommunikation mit dem Arduino und der eigentliche Geschäftsprozess 
     * auf dem Raspberry gestartet werden können. 
     * </p>
     */
    public void doStart()
    {
        logger.debug("doStart()...");
        
        // Kommunikations-Status setzen...
        this.i2cStatus = ArduinoI2C.Status.INITIAL;
        
        // Zustandsgroessen initial in der View setzen...
        setProperty(Model.DATA_KEY, new Data(this.counter, 
                                             this.cycleTime, 
                                             this.token,
                                             this.numberDestinationMA,
                                             this.numberDestinationMB,
                                             this.numberMA,
                                             this.numberMB,
                                             this.outputMA,
                                             this.outputMB,
                                             this.realValueMA,
                                             this.realValueMB));
        
        // Status der GUI setzen..
        setProperty(GUI_STATUS_KEY, GuiStatus.START);        
    }
    
    /**
     * doReset()
     */
    public void doReset()
    {
        logger.debug("doReset()...");
        
        this.token = 0L;
        
        this.numberMA = 0L;
        this.numberMB = 0L;
        
        this.valueDestinationMA = BigDecimal.ZERO;
        this.valueDestinationMB = BigDecimal.ZERO;
        this.numberDestinationMA = 0L;
        this.numberDestinationMB = 0L;
        
        for (int index = 0; index < Model.SIZES_DIFF_VALUES; index++)
        {
            this.cycleTimeValues[index] = BigDecimal.ZERO;
            this.diffValuesMA[index] = 0L;
            this.diffValuesMB[index] = 0L;
        }
        this.realValueMA = BigDecimal.ZERO;
        this.realValueMB = BigDecimal.ZERO;
        
        // Zustandsgroessen zuruecksetzen...
        doClear();
        
        // isControlled: Mit Regelung... 
        this.isControlled = false;
        setProperty(Model.CONTROL_KEY, Boolean.valueOf(this.isControlled));
        
        setProperty(Model.DATA_KEY, new Data(this.counter, 
                                             this.cycleTime, 
                                             this.token,
                                             this.numberDestinationMA,
                                             this.numberDestinationMB,
                                             this.numberMA,
                                             this.numberMB,
                                             this.outputMA,
                                             this.outputMB,
                                             this.realValueMA,
                                             this.realValueMB));
    }
    
    /**
     * doStop() - Methode zum Unterbrechen des Geschaeftsprozess 
     * und der Kommunikation mit derm Arduino.
     */
    public void doStop()
    {
        logger.debug("doStop()...");
        
        // Kommunikations-Status setzen...
        this.i2cStatus = ArduinoI2C.Status.NOP;
        // Status der GUI setzen...
        setProperty(GUI_STATUS_KEY, GuiStatus.STOP); 
        
        // Zustandsgroessen zuruecksetzen...
        doClear();
        
        try
        {
            setPwmMA(0.0F);
            setPwmMB(0.0F);
        }
        catch(IOException exception)
        {
            logger.error(exception.toString(), exception);
            System.err.println(exception.toString());
        }
    }
    
    /**
     * shutdown()...
     * <p>
     * Der gpioController wird auf dem Raspi heruntergefahren...
     * </p>
     */
    public void shutdown()
    {
        logger.debug("shutdown()..."); 

        // Kommunikations-Status setzen...
        this.i2cStatus = ArduinoI2C.Status.NOP;

        setProperty(GUI_STATUS_KEY, GuiStatus.END);        

        try
        {
            setPwmMA(0.0F);
            setPwmMB(0.0F);
        }
        catch(IOException exception)
        {
            logger.error(exception.toString(), exception);
            System.err.println(exception.toString());
        }
        
        if (isRaspi)
        {
            this.gpioController.shutdown();  
        }
    }

    /**
     * doClear() - Zuruecksetzen der rel. Variablen...
     */
    private void doClear()
    {
        this.totalMA[0] = 0L;
        this.totalMA[1] = 0L;
        this.totalMB[0] = 0L;
        this.totalMB[1] = 0L;
        this.controlMA[0] = BigDecimal.ZERO.setScale(MX_VALUE_SCALE);
        this.controlMA[1] = BigDecimal.ZERO.setScale(MX_VALUE_SCALE);
        this.controlMB[0] = BigDecimal.ZERO.setScale(MX_VALUE_SCALE);
        this.controlMB[1] = BigDecimal.ZERO.setScale(MX_VALUE_SCALE);
        this.outputMA = BigDecimal.ZERO.setScale(SCALE_OUTPUT);
        this.outputMB = BigDecimal.ZERO.setScale(SCALE_OUTPUT);
    }
    
    @Override
    public String toString()
    {
        return "gui.Model";
    }
    
    /**
     * GuiStatus -  beschreibt den Status der 
     * Oberflaeche (GUI)
     * <ul>
     *  <li>INIT("Init")</li>
     *  <li>START("Start")</li>
     *  <li>STOP("Stop")</li>
     *  <li>ENDE("Ende")</li>
     * </ul>
     * @author Detlef Tribius
     *
     */
    public enum GuiStatus
    {
        /**
         * INIT("Init") - Initialisierung (nach Programmstart)
         */
        INIT("Init"),
        /**
         * START("Start") - Nach Betaetigung des Start-Button
         */
        START("Start"),
        /**
         * STOP("Stop") - Nach Betaetigung des Stop-Button
         */
        STOP("Stop"),
        /**
         * END("Ende") - Nach Betaetigung des Ende-Button
         */
        END("Ende");
        /**
         * GuiStatus - priv. Konstruktor
         * @param guiStatus
         */
        private GuiStatus(String guiStatus)
        {
            this.guiStatus = guiStatus;   
        }
        
        /**
         * guiStatus - textuelle Beschreibung des Gui-Status
         */
        private final String guiStatus;
        
        /**
         * getGuiStatus()
         * @return guiStatus
         */
        public String getGuiStatus()
        {
            return this.guiStatus;
        }
        
        /**
         * toString() - zu Protokollzwecken...
         */
        public String toString()
        {
            return new StringBuilder().append("[")
                                      .append(this.guiStatus)
                                      .append("]")
                                      .toString();
        }
    }
}
