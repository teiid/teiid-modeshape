/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.schematic.internal.document;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.codehaus.jackson.JsonToken;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.modeshape.schematic.document.Binary;
import org.modeshape.schematic.document.Code;
import org.modeshape.schematic.document.CodeWithScope;
import org.modeshape.schematic.document.Document;
import org.modeshape.schematic.document.Json;
import org.modeshape.schematic.document.MaxKey;
import org.modeshape.schematic.document.MinKey;
import org.modeshape.schematic.document.ObjectId;
import org.modeshape.schematic.document.Symbol;
import org.modeshape.schematic.document.Timestamp;
import org.modeshape.schematic.internal.annotation.FixFor;
import org.modeshape.schematic.internal.io.BufferCache;

public class BsonReadingAndWritingTest {

    protected BsonReader reader;
    protected BsonWriter writer;
    protected Document input;
    protected Document output;
    protected boolean print;

    @Before
    public void beforeTest() {
        reader = new BsonReader();
        writer = new BsonWriter();
        print = false;
    }

    @After
    public void afterTest() {
        reader = null;
        writer = null;
    }

    @Test
    public void shouldReadExampleBsonStream() throws IOException {
        // "\x16\x00\x00\x00\x02hello\x00\x06\x00\x00\x00world\x00\x00"
        byte[] bytes = new byte[] {0x16, 0x00, 0x00, 0x00, 0x02, 0x68, 0x65, 0x6c, 0x6c, 0x6f, 0x00, 0x06, 0x00, 0x00, 0x00,
            0x77, 0x6f, 0x72, 0x6c, 0x64, 0x00, 0x00};
        output = reader.read(new ByteArrayInputStream(bytes));
        String json = Json.write(output);
        String expected = "{ \"hello\" : \"world\" }";
        if (print) {
            System.out.println(json);
            System.out.flush();
        }
        assertEquals(expected, json);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithStringValue() {
        input = new BasicDocument("name", "Joe");
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithBooleanValue() {
        input = new BasicDocument("foo", 3L);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithIntValue() {
        input = new BasicDocument("foo", 3);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithLongValue() {
        input = new BasicDocument("foo", 3L);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithFloatValue() {
        input = new BasicDocument("foo", 3.0f);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithDoubleValue() {
        input = new BasicDocument("foo", 3.0d);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithDateValue() {
        input = new BasicDocument("foo", new Date());
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithTimestampValue() {
        input = new BasicDocument("foo", new Timestamp(new Date()));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithObjectId() {
        // print = true;
        int time = Math.abs((int) new Date().getTime());
        if (print) System.out.println("time value: " + time);
        input = new BasicDocument("foo", new ObjectId(time, 1, 2, 3));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithCode() {
        input = new BasicDocument("foo", new Code("bar"));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithCodeWithScope() {
        Document scope = new BasicDocument("baz", "bam", "bak", "bat");
        input = new BasicDocument("foo", new CodeWithScope("bar", scope));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithMaxKey() {
        input = new BasicDocument("foo", MaxKey.getInstance());
        assertRoundtrip(input, false);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithMinKey() {
        input = new BasicDocument("foo", MinKey.getInstance());
        assertRoundtrip(input, false);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithSymbol() {
        input = new BasicDocument("foo", new Symbol("bar"));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithNull() {
        input = new BasicDocument("foo", null);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithBinary1() {
        byte[] data = new byte[] {0x16, 0x00, 0x00, 0x00, 0x02, 0x68, 0x65, 0x6c};
        input = new BasicDocument("foo", new Binary(data));
        assertRoundtrip(input);
    }

    @Test
    //Fix-For MODE-1575
    public void shouldRoundTripSimpleBsonObjectWithBinary2() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream is = getClass().getClassLoader().getResourceAsStream("binary");
        assertNotNull(is);
        try {
            byte[] buff = new byte[1024];
            int read;
            while ((read = is.read(buff)) != -1) {
                bos.write(buff, 0, read);
            }
        } finally {
            bos.close();
            is.close();
        }

        input = new BasicDocument("foo", new Binary(bos.toByteArray()));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithUuid() {
        input = new BasicDocument("foo", UUID.randomUUID());
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithPattern() {
        // print = true;
        input = new BasicDocument("foo", Pattern.compile("[CH]at\\s+"));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithPatternAndFlags() {
        // print = true;
        input = new BasicDocument("foo", Pattern.compile("[CH]at\\s+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripSimpleBsonObjectWithArray() {
        BasicArray array = new BasicArray();
        array.addValue("value1");
        array.addValue(new Symbol("value2"));
        array.addValue(30);
        array.addValue(40L);
        array.addValue(4.33d);
        array.addValue(false);
        array.addValue(null);
        array.addValue("value2");
        input = new BasicDocument("foo", array);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripBsonObjectWithTwoFields() {
        input = new BasicDocument("name", "Joe", "age", 35);
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripBsonObjectWithThreeFields() {
        input = new BasicDocument("name", "Joe", "age", 35, "nick", "joey");
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripBsonObjectWithNestedDocument() {
        BasicDocument address = new BasicDocument("street", "100 Main", "city", "Springfield", "zip", 12345);
        input = new BasicDocument("name", "Joe", "age", 35, "address", address, "nick", "joey");
        assertRoundtrip(input);
    }

    @Test
    public void shouldRoundTripLargeModeShapeDocument() throws Exception {
        Document doc = Json.read(resource("json/sample-large-modeshape-doc.json"));
        // OutputStream os = new FileOutputStream("src/test/resources/json/sample-large-modeshape-doc2.json");
        // Json.writePretty(doc, os);
        // os.flush();
        // os.close();
        assertRoundtrip(doc);
    }

    @Test
    @FixFor( "MODE-2074" )
    public void shouldRoundTripBsonWithLargeStringField() throws Exception {
        //use a string which overflows the default buffer BufferCache.MINIMUM_SIZE
        final String largeString = readFile("json/sample-large-modeshape-doc3.json");
        Document document = new BasicDocument("largeString", largeString);
        assertRoundtrip(document);
    }

    @Test
    @FixFor( "MODE-2074" )
    public void shouldRoundTripBsonWithLargeStringFieldFromMultipleThreads() throws Exception {
        final String largeString = readFile("json/sample-large-modeshape-doc3.json");
        int threadCount = 10;
        List<Future<Void>> results = new ArrayList<Future<Void>>();
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            results.add(executorService.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    Document document = new BasicDocument("largeString", largeString);
                    assertRoundtrip(document);
                    return null;
                }
            }));
        }

        for (Future<Void> result : results) {
            result.get(1, TimeUnit.SECONDS);
        }
    }
    
    @Test
    @FixFor( "MODE-2430" )
    public void shouldRoundTripLargeStringsSuccessively() throws Exception {
        int limit = 1048 * 8; // the default buffer size
        int iterations = 20;
        char letter = 'a';
        for (int i = 0; i < iterations; i++) {
            int size = limit + i;
            char[] chars = new char[size];
            Arrays.fill(chars, letter);
            letter = (char)((byte) letter + 1);
            String str = new String(chars);
            Document document = new BasicDocument("largeString", str);
            assertRoundtrip(document, false);
        }
    }

    @Test
    @FixFor( "MODE-2615" )
    public void shouldRoundTripDocumentWithMultiByteUTF8Chars() throws Exception{
        char[] chars = new char[BufferCache.MINIMUM_SIZE];
        Arrays.fill(chars, 'a');
        chars[BufferCache.MINIMUM_SIZE - 1] = '\u00A3'; // 2 bytes UTF-8
        Document document = new BasicDocument("string", new String(chars));
        assertRoundtrip(document);

        chars[BufferCache.MINIMUM_SIZE - 1] = '\uFFFF'; // 3 bytes UTF-8
        document = new BasicDocument("string", new String(chars));
        assertRoundtrip(document);

        chars[BufferCache.MINIMUM_SIZE - 1] = '\u00A3'; // 2 bytes UTF-8
        chars[BufferCache.MINIMUM_SIZE - 2] = '\uFFFF'; // 3 bytes UTF-8
        document = new BasicDocument("string", new String(chars));
        assertRoundtrip(document);

        chars[BufferCache.MINIMUM_SIZE - 1] = '\uFFFF'; // 3 bytes UTF-8
        chars[BufferCache.MINIMUM_SIZE - 2] = '\u00A3'; // 2 bytes UTF-8
        document = new BasicDocument("string", new String(chars));
        assertRoundtrip(document);
    }

    protected String readFile(String filePath) throws IOException {
        InputStreamReader reader = new InputStreamReader(resource(filePath));
        StringBuilder stringBuilder = new StringBuilder();
        boolean error = false;
        try {
            int numRead = 0;
            char[] buffer = new char[1024];
            while ((numRead = reader.read(buffer)) > -1) {
                stringBuilder.append(buffer, 0, numRead);
            }
        } catch (IOException e) {
            error = true; // this error should be thrown, even if there is an error closing reader
            throw e;
        } catch (RuntimeException e) {
            error = true; // this error should be thrown, even if there is an error closing reader
            throw e;
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                if (!error) throw e;
            }
        }
        return stringBuilder.toString();
    }

    protected void assertRoundtrip( Document input ) {
        assertRoundtrip(input, true);
    }

    protected void assertRoundtrip( Document input,
                                    boolean compareToOtherImpls ) {
        assertNotNull(input);
        Document output = writeThenRead(input, compareToOtherImpls);
        if (print) {
            System.out.println("********************************************************************************");
            System.out.println("INPUT :  " + input);
            System.out.println();
            System.out.println("OUTPUT:  " + output);
            System.out.println("********************************************************************************");
            System.out.flush();
        }
        Assert.assertEquals("Round trip failed", input, output);
    }

    protected Document writeThenRead( Document object,
                                      boolean compareToOtherImpls ) {
        try {
            byte[] bytes = writer.write(object);

            Document result = reader.read(new ByteArrayInputStream(bytes));

            return result;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    protected String time( long nanos ) {
        return "" + TimeUnit.NANOSECONDS.convert(nanos, TimeUnit.NANOSECONDS) + "ns";
    }

    protected String percent( long nanos1,
                              long nanos2 ) {
        float percent = 100.0f * (float)(((double)nanos2 - (double)nanos1) / nanos1);
        if (percent < 0.0d) {
            return "" + -percent + "% slower";
        }
        return "" + percent + "% faster";
    }

    protected Map<String, Object> createJacksonData( Document document ) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        for (Document.Field field : document.fields()) {
            Object value = field.getValue();
            data.put(field.getName(), createJacksonData(value));
        }
        return data;
    }

    protected Object createJacksonData( Object value ) {
        if (value instanceof MinKey) {
            value = JsonToken.VALUE_STRING;
        } else if (value instanceof MaxKey) {
            value = JsonToken.VALUE_STRING;
        } else if (value instanceof Symbol) {
            value = new de.undercouch.bson4jackson.types.Symbol(((Symbol)value).getSymbol());
        } else if (value instanceof ObjectId) {
            ObjectId id = (ObjectId)value;
            value = new de.undercouch.bson4jackson.types.ObjectId(id.getTime(), id.getMachine(), id.getInc());
        } else if (value instanceof Timestamp) {
            Timestamp ts = (Timestamp)value;
            value = new de.undercouch.bson4jackson.types.Timestamp(ts.getTime(), ts.getInc());
        } else if (value instanceof CodeWithScope) {
            CodeWithScope code = (CodeWithScope)value;
            value = new de.undercouch.bson4jackson.types.JavaScript(code.getCode(), createJacksonData(code.getScope()));
        } else if (value instanceof Code) {
            Code code = (Code)value;
            value = new de.undercouch.bson4jackson.types.JavaScript(code.getCode(), null);
        } else if (value instanceof List) {
            List<?> values = (List<?>)value;
            List<Object> newValues = new ArrayList<Object>(values.size());
            for (Object v : values) {
                newValues.add(createJacksonData(v));
            }
            value = newValues;
        } else if (value instanceof Document) {
            value = createJacksonData((Document)value);
        }
        return value;
    }

    protected void assertSame( byte[] b1,
                               byte[] b2,
                               String name1,
                               String name2 ) {
        if (b1.equals(b2)) return;
        int s1 = b1.length;
        int s2 = b2.length;
        String sb1 = toString(b1);
        String sb2 = toString(b2);
        if (!sb1.equals(sb2)) {
            System.out.println(name1 + " size: " + padLeft(s1, 3) + " content: " + sb1);
            System.out.println(name2 + " size: " + padLeft(s2, 3) + " content: " + sb2);
            fail();
        }
    }

    protected String padLeft( Object value,
                              int width ) {
        String result = value != null ? value.toString() : "null";
        while (result.length() < width) {
            result = " " + result;
        }
        return result;
    }

    protected String toString( byte[] bytes ) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(padLeft((int)b, 4)).append(' ');
        }
        return sb.toString();
    }

    protected boolean delete( File fileOrDirectory ) {
        if (fileOrDirectory == null) {
            return false;
        }
        if (!fileOrDirectory.exists()) {
            return false;
        }

        // The file/directory exists, so if a directory delete all of the contents ...
        if (fileOrDirectory.isDirectory()) {
            for (File childFile : fileOrDirectory.listFiles()) {
                delete(childFile); // recursive call (good enough for now until we need something better)
            }
            // Now an empty directory ...
        }
        // Whether this is a file or empty directory, just delete it ...
        return fileOrDirectory.delete();
    }

    protected InputStream resource( String resourcePath ) {
        InputStream stream = BsonReadingAndWritingTest.class.getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            File file = new File(resourcePath);
            if (!file.exists()) {
                file = new File("src/test/resources" + resourcePath);
            }
            if (!file.exists()) {
                file = new File("src/test/resources/" + resourcePath);
            }
            if (file.exists()) {
                try {
                    stream = new FileInputStream(file);
                } catch (IOException e) {
                    throw new AssertionError("Failed to open stream to \"" + file.getAbsolutePath() + "\"");
                }
            }
        }
        assert stream != null : "Resource at \"" + resourcePath + "\" could not be found";
        return stream;
    }

}
