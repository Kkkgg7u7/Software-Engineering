package com.werewolf.media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.IOException;

@Component
public class LocalImageStorage implements ImageStorage {
    private final Path directory;
    public LocalImageStorage(@Value("${jiujiu.media.directory:./data/media}") String location) throws IOException {
        directory=Path.of(location).toAbsolutePath().normalize();Files.createDirectories(directory);
    }
    private Path path(String name) {
        if(!name.matches("[a-f0-9]{32}\\.png"))throw new IllegalArgumentException("资源名称不正确");
        Path result=directory.resolve(name).normalize();
        if(!result.startsWith(directory))throw new IllegalArgumentException("资源路径不正确");
        return result;
    }
    public void save(String name,byte[] data) throws IOException {Files.write(path(name),data,StandardOpenOption.CREATE_NEW);}
    public Resource load(String name) throws IOException {Path file=path(name);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new NoSuchFileException(name);return new FileSystemResource(file);}
    public void discard(String name) throws IOException {Files.deleteIfExists(path(name));}
}
