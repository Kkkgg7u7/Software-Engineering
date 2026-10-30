package com.werewolf.media;
import org.springframework.core.io.Resource;
import java.io.IOException;
public interface ImageStorage {
    void save(String name,byte[] data) throws IOException;
    Resource load(String name) throws IOException;
    void discard(String name) throws IOException;
}
