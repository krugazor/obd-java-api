package com.github.pires.obd.commands;

import com.github.pires.obd.commands.protocol.OBDReadCurrentProtocol;
import com.github.pires.obd.exceptions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import android.util.Log;

/**
 * Base OBD command.
 *
 * @author pires
 * @version $Id: $Id
 */
public abstract class ObdCommand {

    /**
     * Error classes to be tested in order
     */
    private final Class[] ERROR_CLASSES = {
            UnableToConnectException.class,
            BusInitException.class,
            MisunderstoodCommandException.class,
            NoDataException.class,
            StoppedException.class,
            UnknownErrorException.class,
            UnsupportedCommandException.class
    };
    protected ArrayList<Integer> buffer = null;
    protected String cmd = null;
    protected boolean useImperialUnits = false;
    protected String rawData = null;
    protected long responseTimeDelay = 200;
    private long start;
    private long end;

    /**
     * Default ctor to use
     *
     * @param command the command to send
     */
    public ObdCommand(String command) {
        this.cmd = command;
        this.buffer = new ArrayList<>();
    }

    /**
     * Prevent empty instantiation
     */
    private ObdCommand() {
    }

    /**
     * Copy ctor.
     *
     * @param other the ObdCommand to copy.
     */
    public ObdCommand(ObdCommand other) {
        this(other.cmd);
    }

    /**
     * Sends the OBD-II request and deals with the response.
     * <p>
     * This method CAN be overriden in fake commands.
     *
     * @param in  a {@link java.io.InputStream} object.
     * @param out a {@link java.io.OutputStream} object.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    public void run(InputStream in, OutputStream out) throws IOException,
            InterruptedException {
        start = System.currentTimeMillis();
        sendCommand(out);
        readResult(in);
        end = System.currentTimeMillis();
    }

    /**
     * Sends the OBD-II request.
     * <p>
     * This method may be overriden in subclasses, such as ObMultiCommand or
     * TroubleCodesCommand.
     *
     * @param out The output stream.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    protected void sendCommand(OutputStream out) throws IOException,
            InterruptedException {
        // write to OutputStream (i.e.: a BluetoothSocket) with an added
        // Carriage return
        out.write((cmd + "\r").getBytes());
        out.flush();

    }

    /**
     * Resends this command.
     *
     * @param out a {@link java.io.OutputStream} object.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    protected void resendCommand(OutputStream out) throws IOException,
            InterruptedException {
        out.write("\r".getBytes());
        out.flush();
    }

    /**
     * Reads the OBD-II response.
     * <p>
     * This method may be overriden in subclasses, such as ObdMultiCommand.
     *
     * @param in a {@link java.io.InputStream} object.
     * @throws java.io.IOException if any.
     */
    protected void readResult(InputStream in) throws IOException {
        readRawData(in);
        checkForErrors();
        fillBuffer();
        performCalculations();
    }

    /**
     * This method exists so that for each command, there must be a method that is
     * called only once to perform calculations.
     */
    protected abstract void performCalculations();

    /**
     * <p>fillBuffer.</p>
     */
    protected void fillBuffer() {
        rawData = rawData.replaceAll("\\s", ""); //removes all [ \t\n\x0B\f\r]
        rawData = rawData.replaceAll("(BUS INIT)|(BUSINIT)|(\\.)", "");

        if (!rawData.matches("([0-9A-F])+")) {
            throw new NonNumericResponseException(rawData);
        }

        // read string each two chars
        buffer.clear();
        int begin = 0;
        int end = 2;
        while (end <= rawData.length()) {
            buffer.add(Integer.decode("0x" + rawData.substring(begin, end)));
            begin = end;
            end += 2;
        }
    }

    /// utility function for multilines, but may be reused elsewhere
    public static int countOccurences(String s, char c) {
        int counter = 0;
        for( int i=0; i<s.length(); i++ ) {
            if( s.charAt(i) == c ) {
                counter++;
            }
        }

        return counter;
    }

    /**
     * <p>
     * readRawData.</p>
     *
     * @param in a {@link java.io.InputStream} object.
     * @throws java.io.IOException if any.
     */
    protected void readRawData(InputStream in) throws IOException {
        byte b = 0;
        StringBuilder res = new StringBuilder();

        // TODO : multiline
        // read until '>' arrives OR end of stream reached
        char c = '@';
        boolean multiline = false;
        boolean potential = false;
        boolean hasAtLeastHeaderSize = false;
        // -1 if the end of the stream is reached
        while (((b = (byte) in.read()) > -1)) {
            if(c == '@' && ((char)b) != '4')
                potential = true;
            c = (char) b;
            if(c == ':' && !multiline)
                multiline = true;
            if (c == '>') // read until '>' arrives
            {
                String currentStr = res.toString();
                int header = -1;

                if(multiline) {
                    // we already have :
                    int headerlength = currentStr.indexOf(':');
                    String headerStr = currentStr.substring(0,headerlength-1).replaceAll("[ \r]", "");
                    header = Integer.parseInt(headerStr, 16);
                } else {
                    try {
                        header = Integer.parseInt(currentStr, 16);
                    } catch (Exception e) {
                    }
                }

                if(header >= 0 && !(this instanceof OBDReadCurrentProtocol)) {
                    potential = true;
                    multiline = true;

                    // number of elements = number of spaces - 1(header) - number of numbered lines (:)
                    hasAtLeastHeaderSize = (countOccurences(currentStr, ' ') - 1 - countOccurences(currentStr, ':') >= header);
                }
                if(!potential || !multiline) {
                    break;
                }
                else if(potential && multiline && hasAtLeastHeaderSize)
                    break;
                else {
                    // investigate
                    Log.d("INSPECT", "Not breaking for "+ currentStr);

                    // DEBUG : let's say it's all good...
                    if(currentStr.endsWith("\r\r")) {
                        // put it all on one line
                        String result = currentStr.substring(currentStr.indexOf(':') + 1);
                        result = result.replaceAll("[0-9A-F]: ", ""); // trim the headers
                        res = new StringBuilder(result);
                        break;
                    }
                }
            }
            res.append(c);
        }

    /*
     * Imagine the following response 41 0c 00 0d.
     *
     * ELM sends strings!! So, ELM puts spaces between each "byte". And pay
     * attention to the fact that I've put the word byte in quotes, because 41
     * is actually TWO bytes (two chars) in the socket. So, we must do some more
     * processing..
     */
        rawData = res.toString().replaceAll("SEARCHING", "");

    /*
     * Data may have echo or informative text like "INIT BUS..." or similar.
     * The response ends with two carriage return characters. So we need to take
     * everything from the last carriage return before those two (trimmed above).
     */
        //kills multiline.. rawData = rawData.substring(rawData.lastIndexOf(13) + 1);
        rawData = rawData.replaceAll("\\s", "");//removes all [ \t\n\x0B\f\r]
    }

    void checkForErrors() {
        for (Class<? extends ResponseException> errorClass : ERROR_CLASSES) {
            ResponseException messageError;

            try {
                messageError = errorClass.newInstance();
                messageError.setCommand(this.cmd);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            if (messageError.isError(rawData)) {
                throw messageError;
            }
        }
    }

    /**
     * <p>getResult.</p>
     *
     * @return the raw command response in string representation.
     */
    public String getResult() {
        return rawData;
    }

    /**
     * <p>getFormattedResult.</p>
     *
     * @return a formatted command response in string representation.
     */
    public abstract String getFormattedResult();

    /**
     * <p>getCalculatedResult.</p>
     *
     * @return the command response in string representation, without formatting.
     */
    public abstract String getCalculatedResult();

    /**
     * <p>Getter for the field <code>buffer</code>.</p>
     *
     * @return a list of integers
     */
    protected ArrayList<Integer> getBuffer() {
        return buffer;
    }

    /**
     * <p>useImperialUnits.</p>
     *
     * @return true if imperial units are used, or false otherwise
     */
    public boolean useImperialUnits() {
        return useImperialUnits;
    }

    /**
     * The unit of the result, as used in {@link #getFormattedResult()}
     *
     * @return a String representing a unit or "", never null
     */
    public String getResultUnit() {
        return "";//no unit by default
    }

    /**
     * Set to 'true' if you want to use imperial units, false otherwise. By
     * default this value is set to 'false'.
     *
     * @param isImperial a boolean.
     */
    public void useImperialUnits(boolean isImperial) {
        this.useImperialUnits = isImperial;
    }

    /**
     * <p>getName.</p>
     *
     * @return the OBD command name.
     */
    public abstract String getName();

    /**
     * Time the command waits before returning from #sendCommand()
     *
     * @return delay in ms
     */
    public long getResponseTimeDelay() {
        return responseTimeDelay;
    }

    /**
     * Time the command waits before returning from #sendCommand()
     *
     * @param responseTimeDelay a long.
     */
    public void setResponseTimeDelay(long responseTimeDelay) {
        this.responseTimeDelay = responseTimeDelay;
    }

    //fixme resultunit
    /**
     * <p>Getter for the field <code>start</code>.</p>
     *
     * @return a long.
     */
    public long getStart() {
        return start;
    }

    /**
     * <p>Setter for the field <code>start</code>.</p>
     *
     * @param start a long.
     */
    public void setStart(long start) {
        this.start = start;
    }

    /**
     * <p>Getter for the field <code>end</code>.</p>
     *
     * @return a long.
     */
    public long getEnd() {
        return end;
    }

    /**
     * <p>Setter for the field <code>end</code>.</p>
     *
     * @param end a long.
     */
    public void setEnd(long end) {
        this.end = end;
    }

    /**
     * <p>getCommandPID.</p>
     *
     * @return a {@link java.lang.String} object.
     * @since 1.0-RC12
     */
    public final String getCommandPID() {
        return cmd.substring(3);
    }

}
