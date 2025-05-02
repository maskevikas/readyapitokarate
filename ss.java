import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

public class ReadyApiToKarateConverter {
    private final String inputFile;
    private final String outputDir;
    private Document document;
    private XPath xpath;

    public ReadyApiToKarateConverter(String inputFile, String outputDir) {
        this.inputFile = inputFile;
        this.outputDir = outputDir;
        this.xpath = XPathFactory.newInstance().newXPath();
        
        // Set up namespace context
        final Map<String, String> namespaces = new HashMap<>();
        namespaces.put("con", "http://eviware.com/soapui/config");
        namespaces.put("ns1", "http://www.soapui.org/schemas/soapui/1.0");
        
        this.xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return namespaces.getOrDefault(prefix, "");
            }

            @Override
            public String getPrefix(String uri) {
                for (Map.Entry<String, String> entry : namespaces.entrySet()) {
                    if (entry.getValue().equals(uri)) {
                        return entry.getKey();
                    }
                }
                return null;
            }

            @Override
            public Iterator<String> getPrefixes(String uri) {
                List<String> prefixes = new ArrayList<>();
                for (Map.Entry<String, String> entry : namespaces.entrySet()) {
                    if (entry.getValue().equals(uri)) {
                        prefixes.add(entry.getKey());
                    }
                }
                return prefixes.iterator();
            }
        });
    }

    public boolean parseXml() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new File(inputFile));
            return true;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            System.err.println("Error parsing XML file: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> extractTestCases() {
        List<Map<String, Object>> testCases = new ArrayList<>();

        try {
            // Find all test suites
            NodeList testSuites = (NodeList) xpath.evaluate("//con:testSuite", document, XPathConstants.NODESET);

            for (int i = 0; i < testSuites.getLength(); i++) {
                Node testSuite = testSuites.item(i);
                String suiteName = ((Element) testSuite).getAttribute("name");
                if (suiteName.isEmpty()) {
                    suiteName = "UnknownSuite";
                }

                // Find all test cases in this suite
                NodeList testCaseNodes = (NodeList) xpath.evaluate(".//con:testCase", testSuite, XPathConstants.NODESET);

                for (int j = 0; j < testCaseNodes.getLength(); j++) {
                    Element testCase = (Element) testCaseNodes.item(j);
                    String caseName = testCase.getAttribute("name");
                    if (caseName.isEmpty()) {
                        caseName = "UnknownCase";
                    }

                    Map<String, Object> caseData = new HashMap<>();
                    caseData.put("suite_name", suiteName);
                    caseData.put("name", caseName);
                    caseData.put("steps", new ArrayList<Map<String, Object>>());

                    // Find all test steps in this case
                    NodeList testSteps = (NodeList) xpath.evaluate(".//con:testStep", testCase, XPathConstants.NODESET);

                    for (int k = 0; k < testSteps.getLength(); k++) {
                        Element testStep = (Element) testSteps.item(k);
                        String stepType = testStep.getAttribute("type");
                        String stepName = testStep.getAttribute("name");

                        Map<String, Object> stepData = new HashMap<>();
                        stepData.put("name", stepName);
                        stepData.put("type", stepType);
                        Map<String, Object> stepConfig = new HashMap<>();
                        stepData.put("config", stepConfig);

                        // Handle different step types
                        if ("restrequest".equals(stepType)) {
                            processRestRequest(testStep, stepConfig);
                        } else if ("properties".equals(stepType)) {
                            processProperties(testStep, stepConfig);
                        } else if ("jdbc".equals(stepType)) {
                            processJdbc(testStep, stepConfig);
                        }

                        // Add step to the case
                        ((List<Map<String, Object>>) caseData.get("steps")).add(stepData);
                    }

                    testCases.add(caseData);
                }
            }
        } catch (XPathExpressionException e) {
            System.err.println("Error extracting test cases: " + e.getMessage());
        }

        return testCases;
    }

    private void processRestRequest(Element testStep, Map<String, Object> stepConfig) throws XPathExpressionException {
        // Find config element
        Node configNode = (Node) xpath.evaluate(".//con:config", testStep, XPathConstants.NODE);
        if (configNode != null) {
            Element config = (Element) configNode;
            
            // Extract method
            String method = config.getAttribute("method");
            if (!method.isEmpty()) {
                stepConfig.put("method", method);
            }
            
            // Extract endpoint
            Node endpointNode = (Node) xpath.evaluate(".//con:endpoint", config, XPathConstants.NODE);
            if (endpointNode != null) {
                stepConfig.put("endpoint", endpointNode.getTextContent());
            }
            
            // Extract path/resource
            Node resourceNode = (Node) xpath.evaluate(".//con:resource", config, XPathConstants.NODE);
            if (resourceNode != null) {
                stepConfig.put("path", resourceNode.getTextContent());
            }
            
            // Extract request body
            Node requestNode = (Node) xpath.evaluate(".//con:request", config, XPathConstants.NODE);
            if (requestNode != null) {
                stepConfig.put("request", requestNode.getTextContent());
            }
            
            // Extract headers
            NodeList headerNodes = (NodeList) xpath.evaluate(".//con:entry", config, XPathConstants.NODESET);
            if (headerNodes.getLength() > 0) {
                Map<String, String> headers = new HashMap<>();
                for (int i = 0; i < headerNodes.getLength(); i++) {
                    Element header = (Element) headerNodes.item(i);
                    String key = header.getAttribute("key");
                    String value = header.getAttribute("value");
                    if (!key.isEmpty()) {
                        headers.put(key, value);
                    }
                }
                stepConfig.put("headers", headers);
            }
            
            // Extract assertions
            NodeList assertionNodes = (NodeList) xpath.evaluate(".//con:assertion", testStep, XPathConstants.NODESET);
            if (assertionNodes.getLength() > 0) {
                List<Map<String, String>> assertions = new ArrayList<>();
                
                for (int i = 0; i < assertionNodes.getLength(); i++) {
                    Element assertion = (Element) assertionNodes.item(i);
                    String assertionType = assertion.getAttribute("type");
                    Map<String, String> assertionData = new HashMap<>();
                    assertionData.put("type", assertionType);
                    
                    if ("Simple Contains".equals(assertionType)) {
                        Node tokenNode = (Node) xpath.evaluate(".//con:token", assertion, XPathConstants.NODE);
                        if (tokenNode != null) {
                            assertionData.put("token", tokenNode.getTextContent());
                        }
                    } else if ("Valid HTTP Status Codes".equals(assertionType)) {
                        Node codesNode = (Node) xpath.evaluate(".//con:codes", assertion, XPathConstants.NODE);
                        if (codesNode != null) {
                            assertionData.put("codes", codesNode.getTextContent());
                        }
                    } else if ("JsonPath Match".equals(assertionType)) {
                        Node pathNode = (Node) xpath.evaluate(".//con:path", assertion, XPathConstants.NODE);
                        Node contentNode = (Node) xpath.evaluate(".//con:content", assertion, XPathConstants.NODE);
                        
                        if (pathNode != null) {
                            assertionData.put("path", pathNode.getTextContent());
                        }
                        if (contentNode != null) {
                            assertionData.put("expected", contentNode.getTextContent());
                        }
                    }
                    
                    assertions.add(assertionData);
                }
                
                stepConfig.put("assertions", assertions);
            }
        }
    }

    private void processProperties(Element testStep, Map<String, Object> stepConfig) throws XPathExpressionException {
        NodeList propertyNodes = (NodeList) xpath.evaluate(".//con:property", testStep, XPathConstants.NODESET);
        if (propertyNodes.getLength() > 0) {
            Map<String, String> properties = new HashMap<>();
            for (int i = 0; i < propertyNodes.getLength(); i++) {
                Element property = (Element) propertyNodes.item(i);
                String name = property.getAttribute("name");
                String value = property.getAttribute("value");
                if (!name.isEmpty()) {
                    properties.put(name, value);
                }
            }
            stepConfig.put("properties", properties);
        }
    }

    private void processJdbc(Element testStep, Map<String, Object> stepConfig) throws XPathExpressionException {
        Node configNode = (Node) xpath.evaluate(".//con:config", testStep, XPathConstants.NODE);
        if (configNode != null) {
            // Extract SQL query
            Node queryNode = (Node) xpath.evaluate(".//con:query", configNode, XPathConstants.NODE);
            if (queryNode != null) {
                stepConfig.put("query", queryNode.getTextContent());
            }
            
            // Extract driver
            Node driverNode = (Node) xpath.evaluate(".//con:driver", configNode, XPathConstants.NODE);
            if (driverNode != null) {
                stepConfig.put("driver", driverNode.getTextContent());
            }
            
            // Extract connection string
            Node connectionNode = (Node) xpath.evaluate(".//con:connectionString", configNode, XPathConstants.NODE);
            if (connectionNode != null) {
                stepConfig.put("connection", connectionNode.getTextContent());
            }
        }
    }

    public void convertToKarate(List<Map<String, Object>> testCases) {
        // Create output directory if it doesn't exist
        File outputDirFile = new File(outputDir);
        if (!outputDirFile.exists()) {
            outputDirFile.mkdirs();
        }
        
        // Group test cases by suite
        Map<String, List<Map<String, Object>>> suiteMap = new HashMap<>();
        for (Map<String, Object> testCase : testCases) {
            String suiteName = (String) testCase.get("suite_name");
            
            if (!suiteMap.containsKey(suiteName)) {
                suiteMap.put(suiteName, new ArrayList<>());
            }
            
            suiteMap.get(suiteName).add(testCase);
        }
        
        // Create feature files for each suite
        for (Map.Entry<String, List<Map<String, Object>>> entry : suiteMap.entrySet()) {
            String suiteName = entry.getKey();
            List<Map<String, Object>> suiteTestCases = entry.getValue();
            
            String featureFilePath = Paths.get(outputDir, sanitizeFilename(suiteName) + ".feature").toString();
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(featureFilePath))) {
                // Write feature header
                writer.write("Feature: " + suiteName + "\n\n");
                writer.write("  # This feature file was auto-generated from ReadyAPI test cases\n\n");
                
                // Write each test case as a scenario
                for (Map<String, Object> testCase : suiteTestCases) {
                    writer.write("  Scenario: " + testCase.get("name") + "\n");
                    
                    // Process each step
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> steps = (List<Map<String, Object>>) testCase.get("steps");
                    for (Map<String, Object> step : steps) {
                        writeStepToFeature(writer, step);
                    }
                    
                    writer.write("\n");  // Add space between scenarios
                }
            } catch (IOException e) {
                System.err.println("Error writing feature file: " + e.getMessage());
            }
            
            System.out.println("Created feature file: " + featureFilePath);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeStepToFeature(BufferedWriter writer, Map<String, Object> step) throws IOException {
        String stepType = (String) step.get("type");
        Map<String, Object> config = (Map<String, Object>) step.get("config");
        
        if ("restrequest".equals(stepType)) {
            // Write step name as comment
            writer.write("    # " + step.get("name") + "\n");
            
            // Set URL if endpoint exists
            if (config.containsKey("endpoint")) {
                writer.write("    Given url '" + config.get("endpoint") + "'\n");
            }
            
            // Set path if exists
            if (config.containsKey("path")) {
                writer.write("    And path '" + config.get("path") + "'\n");
            }
            
            // Set headers
            if (config.containsKey("headers")) {
                Map<String, String> headers = (Map<String, String>) config.get("headers");
                if (!headers.isEmpty()) {
                    writer.write("    And headers {\n");
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        writer.write("      '" + header.getKey() + "': '" + header.getValue() + "',\n");
                    }
                    writer.write("    }\n");
                }
            }
            
            // Set request body
            if (config.containsKey("request") && config.get("request") != null) {
                String requestBody = (String) config.get("request");
                
                // Try to parse as JSON to format it properly
                try {
                    JSONObject jsonObject = new JSONObject(requestBody);
                    writer.write("    And request\n");
                    writer.write("    \"\"\"\n");
                    writer.write("    " + jsonObject.toString(2) + "\n");
                    writer.write("    \"\"\"\n");
                } catch (JSONException e) {
                    // If not valid JSON, just write as string
                    writer.write("    And request '" + requestBody + "'\n");
                }
            }
            
            // Method call
            String method = (String) config.getOrDefault("method", "GET");
            writer.write("    When method " + method + "\n");
            
            // Assertions
            if (config.containsKey("assertions")) {
                List<Map<String, String>> assertions = (List<Map<String, String>>) config.get("assertions");
                
                for (Map<String, String> assertion : assertions) {
                    String assertionType = assertion.get("type");
                    
                    if ("Valid HTTP Status Codes".equals(assertionType)) {
                        String codesStr = assertion.getOrDefault("codes", "200");
                        String[] codes = codesStr.split(",");
                        for (String code : codes) {
                            writer.write("    Then status " + code.trim() + "\n");
                        }
                    } else if ("Simple Contains".equals(assertionType)) {
                        String token = assertion.get("token");
                        if (token != null && !token.isEmpty()) {
                            writer.write("    Then match response contains '" + token + "'\n");
                        }
                    } else if ("JsonPath Match".equals(assertionType)) {
                        String path = assertion.get("path");
                        String expected = assertion.get("expected");
                        
                        if (path != null && !path.isEmpty() && expected != null) {
                            // Try to parse expected as JSON
                            try {
                                JSONObject jsonObject = new JSONObject(expected);
                                writer.write("    Then match response." + path.replaceFirst("^\\$\\.", "") + 
                                             " == " + jsonObject.toString() + "\n");
                            } catch (JSONException e) {
                                writer.write("    Then match response." + path.replaceFirst("^\\$\\.", "") + 
                                             " == '" + expected + "'\n");
                            }
                        }
                    }
                }
            }
            
        } else if ("properties".equals(stepType)) {
            writer.write("    # " + step.get("name") + " - Setting properties\n");
            
            if (config.containsKey("properties")) {
                Map<String, String> properties = (Map<String, String>) config.get("properties");
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    writer.write("    * def " + entry.getKey() + " = '" + entry.getValue() + "'\n");
                }
            }
            
        } else if ("jdbc".equals(stepType)) {
            writer.write("    # " + step.get("name") + " - JDBC Request\n");
            
            if (config.containsKey("connection")) {
                writer.write("    * def dbConfig = { url: '" + config.get("connection") + "' }\n");
            }
            
            if (config.containsKey("query")) {
                writer.write("    * def DbUtils = Java.type('com.intuit.karate.demo.util.DbUtils')\n");
                writer.write("    * def db = new DbUtils(dbConfig)\n");
                writer.write("    * def result = db.readRows('''" + config.get("query") + "''')\n");
            }
        }
    }

    private String sanitizeFilename(String name) {
        // Remove invalid characters and replace spaces with underscores
        return name.replaceAll("[\\\\/*?:\"<>|]", "").replace(" ", "_");
    }

    public boolean runConversion() {
        if (!parseXml()) {
            return false;
        }
        
        List<Map<String, Object>> testCases = extractTestCases();
        if (testCases.isEmpty()) {
            System.out.println("No test cases found in the input file.");
            return false;
        }
        
        convertToKarate(testCases);
        return true;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java ReadyApiToKarateConverter <input_file> <output_dir>");
            return;
        }
        
        String inputFile = args[0];
        String outputDir = args[1];
        
        ReadyApiToKarateConverter converter = new ReadyApiToKarateConverter(inputFile, outputDir);
        if (converter.runConversion()) {
            System.out.println("Conversion completed successfully. Output files are in " + outputDir);
        } else {
            System.out.println("Conversion failed.");
        }
    }
}
