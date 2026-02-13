package unit.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ixdar.platform.automation.AutomationRecorder;

public class AutomationRecorderTest {

    @Test
    public void testStartRecordStopWritesDemoJson() throws Exception {
        AutomationRecorder recorder = new AutomationRecorder();
        recorder.start();

        JsonObject rawPayload = new JsonObject();
        rawPayload.addProperty("key", 65);
        recorder.recordRaw("key", rawPayload);

        JsonObject abstractPayload = new JsonObject();
        abstractPayload.addProperty("action", "menu_select");
        recorder.recordAbstract("menu_select", abstractPayload);

        Path temp = Files.createTempFile("ixdar-demo-", ".json");
        JsonObject result = recorder.stop(temp.toString());

        assertTrue(result.get("saved").getAsBoolean());
        assertEquals(temp.toFile().getAbsolutePath(), result.get("file").getAsString());

        String text = Files.readString(temp);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(root.has("rawEvents"));
        assertTrue(root.has("abstractActions"));
        assertEquals(1, root.getAsJsonArray("rawEvents").size());
        assertEquals(1, root.getAsJsonArray("abstractActions").size());
    }

    @Test
    public void testStatusWhenIdle() {
        AutomationRecorder recorder = new AutomationRecorder();
        JsonObject status = recorder.status();
        assertFalse(status.get("recording").getAsBoolean());
        assertEquals(0, status.get("rawEventCount").getAsInt());
        assertEquals(0, status.get("abstractActionCount").getAsInt());
    }
}
