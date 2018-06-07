package cs237;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;

import com.google.common.io.Closeables;

import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.receiver.Receiver;
import scala.Tuple2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SparkAppJava extends Receiver<String> {

    String streamFromHost = "localhost";
    int streamFromPort = 9999;

    // Metadata of Streams
    public static Map<String, Map<String, Integer>> streamMetaMap = new HashMap<>();

    // Event Rulls
    public static Map<String, IRuleMerger> ruleMergerMap = new HashMap<>();
    public static Map<String, List<IRulePredicate>> streamPredicateMap = new HashMap<>();

    public static Logger log = LogManager.getRootLogger();

    /**
     * Load Event Rules
     * TODO - (1) Load the rules list from some file or DB. (2) Also load the java objects from jar files.
     */
    public static void loadEventRules() {

        // (1) Load the Rules from somewhere.
        List<IEventRule> eventRuleList = new ArrayList<>();
        IEventRule rule0 = new Thermometer80LTRule();
        eventRuleList.add(rule0);
        ruleMergerMap.put(rule0.ruleId(), rule0.merger());

        // (2) Construct the Map of stream to List of Predicates;
        eventRuleList.forEach(eventRule -> {
            eventRule.predicateList().forEach(predicate -> {
                if (streamPredicateMap.containsKey(predicate.stream())) {
                     streamPredicateMap.get(predicate.stream()).add(predicate);
                }
                else {
                    streamPredicateMap.put(predicate.stream(), Arrays.asList(predicate));
                }
            });
        });
    }

    /**
     * Apply a predicate on an attribute
     *
     * @param attribute
     * @param predicate
     * @return
     */
    public static boolean applyPredicate(String attribute, IRulePredicate predicate) {

        switch(predicate.operator()) {
            case EQUAL:
                switch(predicate.attributeType()) {
                    case STRING:
                        return attribute.equals(predicate.valueString());
                    case INT:
                        return Integer.valueOf(attribute) == predicate.valueInt();
                    case FLOAT:
                        return Double.valueOf(attribute) == predicate.valueFloat();
                }
                break;
            case CONTAINS:
                switch(predicate.attributeType()) {
                    case STRING:
                        return attribute.contains(predicate.valueString());
                    case INT:
                        return false;
                    case FLOAT:
                        return false;
                }
                break;
            case LESS_THAN:
                switch(predicate.attributeType()) {
                    case STRING:
                        return attribute.compareTo(predicate.valueString()) < 0;
                    case INT:
                        return Integer.valueOf(attribute) < predicate.valueInt();
                    case FLOAT:
                        return Double.valueOf(attribute) < predicate.valueFloat();
                }
                break;
            case LARGER_THAN:
                switch(predicate.attributeType()) {
                    case STRING:
                        return attribute.compareTo(predicate.valueString()) > 0;
                    case INT:
                        return Integer.valueOf(attribute) > predicate.valueInt();
                    case FLOAT:
                        return Double.valueOf(attribute) > predicate.valueFloat();
                }
                break;
            case LESS_EQUAL_THAN:
                switch(predicate.attributeType()) {
                    case STRING:
                        return attribute.compareTo(predicate.valueString()) <= 0;
                    case INT:
                        return Integer.valueOf(attribute) <= predicate.valueInt();
                    case FLOAT:
                        return Double.valueOf(attribute) <= predicate.valueFloat();
                }
                break;
            case LARGER_EQUAL_THAN:
                switch(predicate.attributeType()) {
                    case STRING:
                        return attribute.compareTo(predicate.valueString()) >= 0;
                    case INT:
                        return Integer.valueOf(attribute) >= predicate.valueInt();
                    case FLOAT:
                        return Double.valueOf(attribute) >= predicate.valueFloat();
                }
                break;
            default:
                return false;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {

        String host = "localhost";
        int port = 9999;

        if (args.length == 2) {
            host = args[0];
            port = Integer.parseInt(args[1]);
        }

        // (0) Load Event Rules
        loadEventRules();

        // - DEBUG - //
//        System.out.println("---------streamPredicateMap----------");
//        System.out.println(streamPredicateMap);
//        log.debug("---------streamPredicateMap----------");
//        log.debug(streamPredicateMap);
        // - DEBUG - //

        // (1) New a Stream Receiver
        SparkConf sparkConf = new SparkConf().setAppName("JavaCustomReceiver");
        JavaStreamingContext ssc = new JavaStreamingContext(sparkConf, new Duration(5000));
        // Create an input stream with the custom receiver on target ip:streamFromPort
        JavaReceiverInputDStream<String> lines = ssc.receiverStream(new SparkAppJava(host, port));

        // - DEBUG - //
        System.out.println("---------Original Input----------");
        lines.print(10);
        log.debug("---------Original Input----------");
        log.debug(lines);
        // - DEBUG - //


        // (2) Map the whole stream to different data source streams
        JavaPairDStream<String, List<String>> streams = lines.mapToPair(
                line -> {
                    String[] entries = line.split("\\|");
                    return new Tuple2<>(entries[0], Arrays.asList(entries));
                });

        // - DEBUG - //
//        System.out.println("---------Streams Separated----------");
//        streams.print(10);
//        log.debug("---------Streams Separated----------");
//        log.debug(lines);
        // - DEBUG - //

        // (3) Apply the predicates to each stream
        JavaPairDStream<String, List<String>> predicatesMatchRecords =
                streams.mapToPair((PairFunction<Tuple2<String, List<String>>, String, List<String>>) stream -> {

            // - DEBUG - //
//            System.out.println("========== Process record: ");
//            System.out.println(stream._1() + ":" + stream._2());
            // - DEBUG - //

            String streamName = stream._1();
            if (streamPredicateMap.containsKey(streamName)) {

                // - DEBUG - //
//                System.out.println("========== Stream: " + streamName + " matches to predicates.");
                // - DEBUG - //

                List<IRulePredicate> predicates = streamPredicateMap.get(streamName);
                for (Iterator<IRulePredicate> iter = predicates.iterator(); iter.hasNext();) {

                    IRulePredicate predicate = iter.next();

                    // - DEBUG - //
//                    System.out.println("========== Rule: " + predicate.getParent().ruleId());
//                    System.out.println("========== Predicate: " + predicate.id());
//                    System.out.println("==========     Attribute: " + predicate.attribute());
//                    System.out.println("==========     AttributeType: " + predicate.attributeType());
//                    System.out.println("==========     Operator: " + predicate.operator());
//                    System.out.println("==========     ValueString: " + predicate.valueString());
//                    System.out.println("==========     ValueInt: " + predicate.valueInt());
//                    System.out.println("==========     ValueString: " + predicate.valueFloat());
                    // - DEBUG - //

                    String attribute = predicate.attribute();
                    int indexOfAttribute = streamMetaMap.get(streamName).get(attribute);

                    // - DEBUG - //
//                    System.out.println("========== indexOfAttribute: " + indexOfAttribute);
                    // - DEBUG - //

                    if (applyPredicate(stream._2().get(indexOfAttribute), predicate)) {
                        return new Tuple2<>(predicate.getParent().ruleId(), stream._2());
                    }
                }
            }
            return null;
        }).filter(record -> record != null);

        // - DEBUG - //
        System.out.println("---------Predicates Applied----------");
        predicatesMatchRecords.print(10);
        log.debug("---------Predicates Applied----------");
        log.debug(predicatesMatchRecords);
        // - DEBUG - //

        // (4) Merge the predicates of each Rule
        // TODO - Currently, merge phase only merge the list of records together for each matched rule
        // TODO - Need to find a way to call rule's merger for a list of predicates and decide whether
        // TODO - this record is in the result list or not.
        JavaPairDStream<String, List<String>> rulesMatchRecords = predicatesMatchRecords.reduceByKey(
                (Function2<List<String>, List<String>, List<String>>) (strings1, strings2) -> {

                    List<String> records = new ArrayList<>();

                    StringBuilder record1 = new StringBuilder();
                    strings1.forEach(attr -> {
                        record1.append(attr);
                        record1.append("-");
                    });
                    records.add(record1.toString());

                    StringBuilder record2 = new StringBuilder();
                    strings2.forEach(attr -> {
                        record2.append(attr);
                        record2.append("-");
                    });
                    records.add(record2.toString());

                    return records;
                });

        // - DEBUG - //
//        System.out.println("---------Rules Merged----------");
//        rulesMatchRecords.print(10);
//        log.debug("---------Rules Merged----------");
//        log.debug(rulesMatchRecords);
        // - DEBUG - //

        // ======================== Previous Manually Rules Engine ======================== //
        /*SparkConf sparkConf = new SparkConf().setAppName("JavaCustomReceiver");
        JavaStreamingContext ssc = new JavaStreamingContext(sparkConf, new Duration(5000));
        // Create an input stream with the custom receiver on target ip:streamFromPort
        JavaReceiverInputDStream<String> lines = ssc.receiverStream(new SparkAppJava(host, port));

        // - DEBUG - //
        lines.print(10);
        log.debug("---------Original Input----------");
        log.debug(lines);
        // - DEBUG - //

        // Filter only data begin with "ThermometerObservation";
        JavaDStream<String> thermometerReadings = lines.filter(
                new Function<String, Boolean>() {
                    public Boolean call(String line) {
                        return "ThermometerObservation".equalsIgnoreCase(line.split("\\|")[0]);
                    }}
        );

        // - DEBUG - //
        thermometerReadings.print(10);
        log.debug("---------Thermometer Readings----------");
        log.debug(thermometerReadings);
        // - DEBUG - //

        // Map the data into <sensorID-timestamp, reading>
        JavaPairDStream<String, Integer> sensorReadings = thermometerReadings.mapToPair(
                s -> {
                    try {
                        String sensorID = s.split("\\|")[4];
                        String timestamp = s.split("\\|")[3];
                        Integer reading = Integer.valueOf(s.split("\\|")[2]);
                        return new Tuple2<>(sensorID + "-" + timestamp, reading);
                    } catch (Exception e) {
                        System.err.println("[mapToPair] Exception data: " + s);
                        return new Tuple2<>("", 0);
                    }
                }
        );

        // - DEBUG - //
        sensorReadings.print(10);
        log.debug("---------Sensor Readings----------");
        log.debug(sensorReadings);
        // - DEBUG - //

        // Filter the data for sensorReadings value >= 80
        JavaPairDStream<String, Integer> output = sensorReadings.filter(reading -> reading._2 >= 80);

        // Output these readings.
        output.print();

        // - DEBUG - //
        log.debug("---------Out put-----------");
        log.debug(output);
        // - DEBUG - //*/
        // ======================== Previous Manually Rules Engine ======================== //

        ssc.start();
        ssc.awaitTermination();
    }

    public SparkAppJava(String host_ , int port_) {
        super(StorageLevel.MEMORY_AND_DISK_2());
        streamFromHost = host_;
        streamFromPort = port_;

        Map<String, Integer> ThermometerObservation = new HashMap<>();
        ThermometerObservation.put("id", 1);
        ThermometerObservation.put("temperature", 2);
        ThermometerObservation.put("timeStamp", 3);
        ThermometerObservation.put("sensor_id", 4);

        streamMetaMap.put("ThermometerObservation", ThermometerObservation);
    }

    @Override
    public void onStart() {
        // Start the thread that receives data over a connection
        new Thread(this::receive).start();
    }

    @Override
    public void onStop() {
        // There is nothing much to do as the thread calling receive()
        // is designed to stop by itself isStopped() returns false
    }

    /** Create a socket connection and receive data until receiver is stopped */
    private void receive() {
        try {
            Socket socket = null;
            BufferedReader reader = null;
            try {
                // connect to the server
                socket = new Socket(streamFromHost, streamFromPort);
                reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                // Until stopped or connection broken continue reading
                String userInput;
                while (!isStopped() && (userInput = reader.readLine()) != null) {
                    //System.out.println("Received data '" + userInput + "'");
                    //log.debug("---------Original Input-----------");
                    //log.debug(userInput);
                    store(userInput);
                }
            } finally {
                Closeables.close(reader, /* swallowIOException = */ true);
                Closeables.close(socket,  /* swallowIOException = */ true);
            }
            // Restart in an attempt to connect again when server is active again
            restart("Trying to connect again");
        } catch(ConnectException ce) {
            // restart if could not connect to server
            restart("Could not connect", ce);
        } catch(Throwable t) {
            restart("Error receiving data", t);
        }
    }
}
