import java.nio.file.*;
import me.lucko.spark.paper.proto.SparkSamplerProtos;

/** Independent verification using the server's installed spark 1.10.191 protobuf decoder. */
public final class VerifySparkDecode {
    public static void main(String[] args) throws Exception {
        var profile = SparkSamplerProtos.SamplerData.parseFrom(Files.readAllBytes(Path.of(args[0])));
        var metadata = profile.getMetadata();
        System.out.println("start=" + metadata.getStartTime() + " end=" + metadata.getEndTime()
                + " ticks=" + metadata.getNumberOfTicks() + " interval_us=" + metadata.getInterval());
        for (var thread : profile.getThreadsList()) {
            double milliseconds = thread.getTimesList().stream().mapToDouble(Double::doubleValue).sum();
            System.out.println("thread=" + thread.getName() + " sampled_ms=" + milliseconds + " nodes=" + thread.getChildrenCount());
        }
    }
}
