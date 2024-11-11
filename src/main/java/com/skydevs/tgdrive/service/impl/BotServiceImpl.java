package com.skydevs.tgdrive.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.File;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.SendResponse;
import com.skydevs.tgdrive.dto.ConfigForm;
import com.skydevs.tgdrive.entity.BigFileInfo;
import com.skydevs.tgdrive.entity.FileInfo;
import com.skydevs.tgdrive.mapper.FileMapper;
import com.skydevs.tgdrive.result.PageResult;
import com.skydevs.tgdrive.service.BotService;
import com.skydevs.tgdrive.service.ConfigService;
import com.skydevs.tgdrive.utils.UserFriendly;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class BotServiceImpl implements BotService {

    @Autowired
    private ConfigService configService;
    @Autowired
    private UserFriendly userFriendly;
    @Autowired
    private FileMapper fileMapper;
    private String botToken;
    private String chatId;
    private TelegramBot bot;
    private final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    /*
    @Value("${server.port}")
    private int serverPort;
    private String url;
     */


    /**
     * 设置基本配置
     * @param filename
     */
    public void setBotToken(String filename) {
        ConfigForm config = configService.get(filename);
        if (config == null) {
            log.error("配置加载失败");
            return;
        }
        try {
            botToken = config.getToken();
            chatId = config.getTarget();
        } catch (Exception e) {
            log.error("获取Bot Token失败: {}", e.getMessage());
        }
        /*
        if (appConfig.getUrl() == null || appConfig.getUrl().isEmpty()) {
            url = "http://localhost:" + serverPort;
        } else {
            url = appConfig.getUrl();
        }
         */
        bot = new TelegramBot(botToken);
    }

    /**
     * 分块上传文件
     * @param inputStream
     * @param filename
     * @return
     */
    private List<String> sendFileStreamInChunks(InputStream inputStream, String filename) {
        byte[] buffer = new byte[MAX_FILE_SIZE]; // 10MB 缓冲区
        List<CompletableFuture<String>> futures = new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(5); // 线程池大小

        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream)) {
            int byteRead;
            int partIndex = 0;

            while ((byteRead = bufferedInputStream.read(buffer)) > 0) {
                // 当前块的文件名，第一块为原名
                String partName;
                if (partIndex == 0) {
                    partName = filename;
                } else {
                    partName = filename + "_part" + partIndex;
                }
                partIndex++;

                // 取当前分块数据
                byte[] chunkData = Arrays.copyOf(buffer, byteRead);

                // 提交上传任务，使用CompletableFuture
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> uploadChunk(chunkData, partName), executorService);
                futures.add(future);
            }

            // 等待所有任务完成并按顺序获取结果
            List<String> fileIds = new ArrayList<>();
            for (CompletableFuture<String> future : futures) {
                fileIds.add(future.join()); // 按顺序等待结果
            }
            return fileIds;
        } catch (IOException e) {
            log.error("文件流读取失败：{}", e.getMessage());
            throw new RuntimeException("文件流读取失败");
        } finally {
            executorService.shutdown();
        }
    }

    private String uploadChunk(byte[] chunkData, String partName) {
        SendDocument sendDocument = new SendDocument(chatId, chunkData).fileName(partName);
        SendResponse response = bot.execute(sendDocument);

        // 检查响应
        if (response.isOk() && response.message() != null && (response.message().document() != null || response.message().sticker() != null)) {
            String fileID = response.message().document().fileId() != null ? response.message().document().fileId() : response.message().sticker().fileId();
            log.info("分块上传成功，File ID：{}， 文件名：{}", fileID, partName);
            return fileID;
        } else {
            log.error("分块上传失败，响应信息：{}", response.description());
            throw new RuntimeException("分块上传失败");
        }
    }

    /**
     * 上传文件
     * @param multipartFile
     * @param prefix
     * @return
     */
    @Override
    public String uploadFile(MultipartFile multipartFile, String prefix) {
        try {
            List<String> fileIds = sendFileStreamInChunks(multipartFile.getInputStream(), multipartFile.getOriginalFilename());
            if (fileIds.size() == 1) {
                String fileID = fileIds.get(0);
                FileInfo fileInfo = FileInfo.builder()
                        .fileId(fileID)
                        .size(userFriendly.humanReadableFileSize(multipartFile.getSize()))
                        .uploadTime(LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(ZoneOffset.UTC))
                        .downloadUrl(prefix + "/d/" + fileID)
                        .fileName(multipartFile.getOriginalFilename())
                        .build();
                fileMapper.insertFile(fileInfo);
                return "/d/" + fileID;
            } else {
                String fileID = createRecordFile(multipartFile.getOriginalFilename(), multipartFile.getSize(), fileIds);
                FileInfo fileInfo = FileInfo.builder()
                        .fileId(fileID)
                        .size(userFriendly.humanReadableFileSize(multipartFile.getSize()))
                        .uploadTime(LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(ZoneOffset.UTC))
                        .downloadUrl(prefix + "/d/" + fileID)
                        .fileName(multipartFile.getOriginalFilename())
                        .build();
                fileMapper.insertFile(fileInfo);
                return "/d/" + fileID;
            }
        }catch (IOException e) {
            log.error("文件上传失败，响应信息：{}", e.getMessage());
            throw new RuntimeException("文件上传失败");
        }
    }

    /**
     * 生成recordFile
     * @param originalFileName
     * @param fileSize
     * @param fileIds
     * @return
     * @throws IOException
     */
    public String createRecordFile(String originalFileName, long fileSize, List<String> fileIds) throws IOException {
        BigFileInfo record = new BigFileInfo();
        record.setFileName(originalFileName);
        record.setFileSize(fileSize);
        record.setFileIds(fileIds);
        record.setRecordFile(true);

        // 创建一个系统临时文件，不依赖特定路径
        Path tempFile = Files.createTempFile(originalFileName + ".record", ".json");
        try {
            String jsonString = JSON.toJSONString(record, true);
            Files.write(Paths.get(tempFile.toUri()), jsonString.getBytes());
        } catch (IOException e) {
            log.error("上传记录文件生成失败" + e.getMessage());
            throw new RuntimeException("上传文件生成失败");
        }

        // 上传记录文件到 Telegram
        byte[] fileBytes = Files.readAllBytes(tempFile);
        SendDocument sendDocument = new SendDocument(chatId, fileBytes)
                .fileName(tempFile.getFileName().toString());

        SendResponse response = bot.execute(sendDocument);
        Message message = response.message();
        String recordFileId = message.document().fileId();

        log.info("记录文件上传成功，File ID: " + recordFileId);

        // 删除本地临时文件
        Files.deleteIfExists(tempFile);

        return recordFileId;
    }

    /**
     * 获取完整下载路径
     * @param fileID
     * @return
     */
    public String getFullDownloadPath(String fileID) {
        GetFile getFile = new GetFile(fileID);
        GetFileResponse getFileResponse = bot.execute(getFile);

        File file = getFileResponse.file();
        log.info(bot.getFullFilePath(file));
        return bot.getFullFilePath(file);
    }

    /**
     * 根据文件id获取文件名
     * @param fileID
     * @return
     */
    @Override
    public String getFileNameByID(String fileID) {
        GetFile getFile = new GetFile(fileID);
        GetFileResponse getFileResponse = bot.execute(getFile);
        File file = getFileResponse.file();
        return file.filePath();
    }

    /**
     * 获取文件分页
     * @param page
     * @param size
     * @return
     */
    @Override
    public PageResult getFileList(int page, int size) {
        // 设置分页
        PageHelper.startPage(page, size);
        Page<FileInfo> pageInfo = fileMapper.getAllFiles();
        List<FileInfo> fileInfos = new ArrayList<>();
        for (FileInfo fileInfo : pageInfo) {
            FileInfo fileInfo1 = new FileInfo();
            BeanUtils.copyProperties(fileInfo, fileInfo1);
            fileInfos.add(fileInfo1);
        }
        log.info("文件分页查询");
        return new PageResult((int) pageInfo.getTotal(), fileInfos);
    }


    /**
     * 发送消息
     * @param m
     */
    public void sendMessage(String m) {
        TelegramBot bot = new TelegramBot(botToken);
        bot.execute(new SendMessage(chatId, m));
        log.info("消息发送成功");
    }


    /**
     * 获取bot token
     * @return
     */
    @Override
    public String getBotToken() {
        return botToken;
    }
}