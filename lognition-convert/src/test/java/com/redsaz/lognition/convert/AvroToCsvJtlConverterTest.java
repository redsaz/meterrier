/*
 * Copyright 2017 Redsaz <redsaz@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redsaz.lognition.convert;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.redsaz.lognition.api.exceptions.AppException;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;

/**
 * Test the Avro to CSV-JTL converter.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class AvroToCsvJtlConverterTest extends ConverterBaseTest {

    @Test
    public void testConvert() throws IOException {
        MockPerfData mpd = new MockPerfData(System.currentTimeMillis(),
                240L,
                Arrays.asList(
                        "Another-call-2",
                        "Howdy there this is a call as well",
                        "example-call-1"
                ),
                Arrays.asList(
                        "thread-1",
                        "thread-2",
                        "thread-3",
                        "thread-4",
                        "thread-5"
                ),
                Arrays.asList(
                        "1001",
                        "200"
                ),
                Arrays.asList(
                        "Non Standard code",
                        "Normally we don't see these"
                ));
        File source = createTempFile("source", ".avro");
        mpd.createAvroFile(source);

        File expectedDest = createTempFile("expected", ".csv");
        String expectedHash = mpd.createExportedCsvFile(expectedDest);

        Converter conv = new AvroToCsvJtlConverter();
        File actualDest = createTempFile("actual", ".csv");
        String actualHash = conv.convert(source, actualDest);

        assertContentEquals(actualDest, expectedDest, "The converter did not convert in the way expected.");
        assertBytesEquals(actualDest, expectedDest, "The conversions are not byte-for-byte equal.");
        assertEquals(actualHash, expectedHash, "Hashes differed.");
    }

    @Test
    public void testConvertStreaming() throws IOException {
        MockPerfData mpd = new MockPerfData(System.currentTimeMillis(),
                240L,
                Arrays.asList(
                        "Another-call-2",
                        "Howdy there this is a call as well",
                        "example-call-1"
                ),
                Arrays.asList(
                        "thread-1",
                        "thread-2",
                        "thread-3",
                        "thread-4",
                        "thread-5"
                ),
                Arrays.asList(
                        "1001",
                        "200"
                ),
                Arrays.asList(
                        "Non Standard code",
                        "Normally we don't see these"
                ));
        File source = createTempFile("source", ".avro");
        mpd.createAvroFile(source);

        File expectedDest = createTempFile("expected", ".csv");
        String expectedHash = mpd.createExportedCsvFile(expectedDest);

        AvroToCsvJtlConverter conv = new AvroToCsvJtlConverter();
        File dest = createTempFile("actual", ".csv");
        String actualHash;
        try (InputStream coversionStream = conv.convertStreaming(source);
                HashingOutputStream hos = new HashingOutputStream(Hashing.sha256(), new BufferedOutputStream(new FileOutputStream(dest)))) {
            coversionStream.transferTo(hos);
            actualHash = hos.hash().toString();
        }

        assertContentEquals(dest, expectedDest, "The converter did not convert in the way expected.");
        assertBytesEquals(dest, expectedDest, "The conversions are not byte-for-byte equal.");
        assertEquals(actualHash, expectedHash, "Hashes differed.");
    }

    @Test(expectedExceptions = AppException.class, expectedExceptionsMessageRegExp = "Unable to convert file\\.")
    public void testConvertStreaming_avroNotFound() throws IOException {
        AvroToCsvJtlConverter conv = new AvroToCsvJtlConverter();

        try (InputStream coversionStream = conv.convertStreaming(new File("this-does-not-exist.avro"))) {
        }
    }

}
