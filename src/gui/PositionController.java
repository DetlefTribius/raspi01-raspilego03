/**
 * 
 */
package gui;

import java.math.BigDecimal;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Klasse PositionController realisiert den eigentlichen Regelalgorithmus
 * der Gleichlaufregelung. Der Algorithmus wird durch Beauftragung der 
 * doControl()-Methode realisiert. Die notwendigen Parameter werden zuvor
 * gesetzt.
 * 
 * @author Detlef Tribius
 */
public class PositionController
{

    /**
     * logger...
     */
    private final static Logger logger = LoggerFactory.getLogger(PositionController.class);    
    
    /**
     * Darstellung der Taktzeit...
     */
    public static int SCALE_CYCLE_TIME = 3;
    
    /**
     * SCALE_OUTPUT = 3, Genauigkeit fuer die Ausgabewerte (Nachkommastellen)
     */
    public static int SCALE_OUTPUT = 3;
    
    
    public static int SCALE_INTERN = 6;
    
    /**
     * Zeitpunkt der aktuellen Beauftragung...
     */
    private Instant now = null;
    
    /**
     * Zeitpunkt der letzten Beauftragung...
     */
    private Instant past = null;
    
    /**
     * cycleTimeDecimal - Zykluszeit (Abtast-/Regler-Zeit)
     */
    private BigDecimal cycleTimeDecimal = BigDecimal.ZERO;    
    
    /**
     * enhancement - Reglerverstaerkung...
     * <p>
     * Die Reglerverstaerkung kann ueber die Oberflaeche geaendert werden...
     * </p>
     */
    private BigDecimal enhancement;

    /**
     * wheelSteps - Anzahl der Impulse des Gebers pro Umdrehung
     */
    private final int wheelSteps;
    
    /**
     * PositionController(int wheelSteps)
     * @param wheelSteps: Anzahl der Impulse des Gebers pro Umdrehung
     */
    PositionController(int wheelSteps)
    {
        // Es ist wahrscheinlich sinnvoll zu fordern, dass 
        // wheelSteps >= 1 gelten muss! 
        // (Da evtl. Division durch wheelSteps notwendig wird!)
        this.wheelSteps = (wheelSteps > 1)? wheelSteps : 1;
        this.enhancement = BigDecimal.ZERO;
    }
    
    /**
     * setEnhancement(BigDecimal enhancement) - Reglerverstaerkung setzen
     * @param enhancement
     */
    public void setEnhancement(BigDecimal enhancement)
    {
        this.enhancement = (enhancement != null)? enhancement : BigDecimal.ZERO;
    }
    
    /**
     * getEnhancement() - Reglerverstaerkung
     * @return
     */
    public BigDecimal getEnhancement()
    {
        return this.enhancement;
    }
    
    /**
     * doControl() - Regelalgorithmus...
     * @return Output(output)
     */
    public Output doControl(long numberDestinationMA, long numberMA,        // Soll-Ist Motor A
                            long numberDestinationMB, long numberMB,        // Soll-Ist Motor B 
                            BigDecimal destinationOutputMA,                 // Sollwert-Vorgabe Motor A (ohne Regelung)
                            BigDecimal destinationOutputMB,                 // Sollwert-Vorgabe Motor B (ohne Regelung)
                            BigDecimal maxValueMA,                          // jeweilige Grenzwerte
                            BigDecimal maxValueMB)                          //  
    {
        // diffNumber: Lage-Differenz zwischen Soll - Ist...
        
        // diffNumberMA: Regel-Differenz fuer Motor A, wird zu outputMA...
        final long diffNumberMA = numberDestinationMA - numberMA;
        
        // diffNumberMB: Regel-Differenz fuer Motor B, wird zu outputMB...
        final long diffNumberMB = numberDestinationMB - numberMB;
        
        // Verstarkungsfaktor, hier gehen die Impulse pro Umdrehung mit ein.
        final BigDecimal p_factor = this.enhancement.divide(BigDecimal.valueOf(this.wheelSteps), SCALE_INTERN, BigDecimal.ROUND_CEILING);
        
        final BigDecimal diffOutputMA = BigDecimal.valueOf(diffNumberMA).multiply(p_factor).setScale(SCALE_OUTPUT, BigDecimal.ROUND_HALF_UP);
        final BigDecimal valueMA = destinationOutputMA.add(diffOutputMA);
        
        // VZ: -1, 0 oder +1
        final int signumMA = valueMA.signum(); 
        final boolean isLimitMA = valueMA.abs().compareTo(maxValueMA.abs()) > 0;
        final BigDecimal outputMA = isLimitMA? ((signumMA < 0)? maxValueMA.negate() : maxValueMA.setScale(SCALE_OUTPUT)) : valueMA;
        
        final BigDecimal diffOutputMB = BigDecimal.valueOf(diffNumberMB).multiply(p_factor).setScale(SCALE_OUTPUT, BigDecimal.ROUND_HALF_UP);
        final BigDecimal valueMB =  destinationOutputMB.add(diffOutputMB);
        
        // VZ: -1, 0 oder +1
        final int signumMB = valueMB.signum(); 
        final boolean isLimitMB = valueMB.abs().compareTo(maxValueMB.abs()) > 0;
        final BigDecimal outputMB = isLimitMB? ((signumMB < 0)? maxValueMB.negate() : maxValueMB) : valueMB;

        // *** Anmerkung ***
        // firstBigDecimal.compareTo(secondBigDecimal) < 0     // "<"
        // firstBigDecimal.compareTo(secondBigDecimal) > 0     // ">"    
        // firstBigDecimal.compareTo(secondBigDecimal) == 0    // "=="  
        // firstBigDecimal.compareTo(secondBigDecimal) >= 0    // ">="
        
        return new Output(BigDecimal.valueOf(diffNumberMA),                         // Lagedifferenz Motor A
                          BigDecimal.valueOf(diffNumberMB),                         // Lagedifferenz Motor B
                          diffOutputMA,                                             // Reglerausgang zum Motor A
                          diffOutputMB,                                             // Reglerausgang zum Motor B
                          outputMA.setScale(SCALE_OUTPUT, BigDecimal.ROUND_FLOOR),  // Ausgang zum Motor A
                          outputMB.setScale(SCALE_OUTPUT, BigDecimal.ROUND_FLOOR)); // Ausgang zum Motor B
    }
    
    
    /**
     * Output - Zusammenfassung des Reglerausgangs
     * 
     * @author Detlef Tribius
     *
     */
    final class Output
    {
        /**
         * BigDecimal diffValueMA - Differenz Lage Motor A (in Impulse)
         */
        private final BigDecimal diffValueMA;
        
        /**
         * BigDecimal diffValueMB - Differenz Lage Motor B (in Impulse)
         */
        private final BigDecimal diffValueMB;
        
        /**
         * diffOutputMA - Reglerausgang fuer Motor A
         * <p>
         * diffOutputMA ergibt sich aus Reglerverstaerkung (p_factor) mal diffValueMA
         * </p>
         */
        private final BigDecimal diffOutputMA;

        /**
         * diffOutputMB - Reglerausgang fuer Motor B
         * <p>
         * diffOutputMB ergibt sich aus Reglerverstaerkung (p_factor) mal diffValueMB
         * </p>
         */
        private final BigDecimal diffOutputMB;
        
        /**
         * BigDecimal outputMA - Sollwert fuer Motor A
         */
        private final BigDecimal outputMA;
        
        /**
         * BigDecimal outputMB - Sollwert fuer Motor B
         */
        private final BigDecimal outputMB; 
        
        /**
         * Output() - Konstruktor aus den Attributen...
         * @param diffValueMA - Lagedifferenz Motor A
         * @param diffValueMB - Lagedifferenz Motor B
         * @param outputMA - Sollwert fuer Motor A
         * @param outputMB - Solwert fuer Motor B
         */
        public Output(BigDecimal diffValueMA,
                      BigDecimal diffValueMB,
                      BigDecimal diffOutputMA,
                      BigDecimal diffOutputMB,
                      BigDecimal outputMA,      
                      BigDecimal outputMB)
        {
            this.diffValueMA = (diffValueMA != null)? diffValueMA : BigDecimal.ZERO.setScale(SCALE_OUTPUT);
            this.diffValueMB = (diffValueMB != null)? diffValueMB : BigDecimal.ZERO.setScale(SCALE_OUTPUT);
            
            this.diffOutputMA = (diffOutputMA != null)? diffOutputMA : BigDecimal.ZERO.setScale(SCALE_OUTPUT);
            this.diffOutputMB = (diffOutputMB != null)? diffOutputMB : BigDecimal.ZERO.setScale(SCALE_OUTPUT);
            
            this.outputMA = (outputMA != null)? outputMA : BigDecimal.ZERO.setScale(SCALE_OUTPUT);
            this.outputMB = (outputMB != null)? outputMB : BigDecimal.ZERO.setScale(SCALE_OUTPUT);
        }

        /**
         * @return the diffValueMA
         */
        public final BigDecimal getDiffValueMA()
        {
            return this.diffValueMA;
        }

        /**
         * @return the diffValueMB
         */
        public final BigDecimal getDiffValueMB()
        {
            return this.diffValueMB;
        }

        /**
         * getDiffOutputMA() - Reglerausgang Motor A
         * @return the diffOutputMA
         */
        public final BigDecimal getDiffOutputMA()
        {
            return this.diffOutputMA;
        }

        /**
         * getDiffOutputMB() - Reglerausgang Motor B
         * @return the diffOutputMB
         */
        public final BigDecimal getDiffOutputMB()
        {
            return this.diffOutputMB;
        }

        /**
         * @return the outputMA
         */
        public final BigDecimal getOutputMA()
        {
            return this.outputMA;
        }

        /**
         * @return the outputMB
         */
        public final BigDecimal getOutputMB()
        {
            return this.outputMB;
        }
        
        /**
         * String toString() - zu Protokollzwecken...
         */
        @Override
        public String toString()
        {
            return new StringBuilder().append("[")
                                      .append(this.diffValueMA.toString())
                                      .append(" ")
                                      .append(this.diffValueMB.toString())
                                      .append(" ")
                                      .append(this.diffOutputMA.toString())
                                      .append(" ")
                                      .append(this.diffOutputMB.toString())
                                      .append(" ")
                                      .append(this.outputMA.toString())
                                      .append(" ")
                                      .append(this.outputMB.toString())
                                      .append("]")
                                      .toString();
        }
    }
}
