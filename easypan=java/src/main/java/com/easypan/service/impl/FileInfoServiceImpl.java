package com.easypan.service.impl;


import com.easypan.utils.RedisComponent;
import com.easypan.config.AppConfig;
import com.easypan.entity.constants.Constants;
import com.easypan.entity.dto.SessionWebUserDto;
import com.easypan.entity.dto.UploadResultDto;
import com.easypan.entity.dto.UserSpaceDto;
import com.easypan.entity.enums.*;
import com.easypan.entity.po.FileInfo;
import com.easypan.entity.po.UserInfo;
import com.easypan.entity.query.FileInfoQuery;
import com.easypan.entity.query.SimplePage;
import com.easypan.entity.query.UserInfoQuery;
import com.easypan.entity.vo.PaginationResultVO;
import com.easypan.exception.BusinessException;
import com.easypan.mappers.FileInfoMapper;
import com.easypan.mappers.UserInfoMapper;
import com.easypan.service.FileInfoService;
import com.easypan.utils.DateUtil;
import com.easypan.utils.ProcessUtils;
import com.easypan.utils.ScaleFilter;
import com.easypan.utils.StringTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 文件信息 业务接口实现
 */
@Service
@Slf4j
public class FileInfoServiceImpl implements FileInfoService {

    @Resource
    private FileInfoMapper<FileInfo, FileInfoQuery> fileInfoMapper;

    @Resource
    private RedisComponent redisComponent;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Resource
    private AppConfig appConfig;

    @Resource
    @Lazy
    private FileInfoServiceImpl fileInfoService;


    @Override
    public FileInfo getFileInfoByFileIdAndUserId(String fileId, String userId) {
        return fileInfoMapper.selectByFileIdAndUserId(fileId, userId);
    }

    /**
     * 创建文件夹
     */
    @Override
//    @Transactional
    public FileInfo newFolder(String filePid, String userId, String folderName) {
        // 检查是否还有同名文件夹
        checkFileName(filePid, userId, folderName, FileFolderTypeEnums.FOLDER.getType());

        Date curDate = new Date();
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileId(StringTools.getRandomString(Constants.LENGTH_10));
        fileInfo.setUserId(userId);
        fileInfo.setFilePid(filePid);
        fileInfo.setFileName(folderName);
        fileInfo.setFolderType(FileFolderTypeEnums.FOLDER.getType());
        fileInfo.setCreateTime(curDate);
        fileInfo.setLastUpdateTime(curDate);
        fileInfo.setStatus(FileStatusEnums.USING.getStatus());
        fileInfo.setDelFlag(FileDelFlagEnums.USING.getFlag());
        fileInfoMapper.insert(fileInfo);
        return fileInfo;
    }

    @Override
    public List<FileInfo> findListByParam(FileInfoQuery param) {
        return fileInfoMapper.selectList(param);
    }

    @Override
    public List<FileInfo> loadAllFolder(String userId, String filePid, String currentFileIds) {
        FileInfoQuery query = new FileInfoQuery();
        query.setUserId(userId);
        query.setFilePid(filePid);
        query.setFolderType(FileFolderTypeEnums.FOLDER.getType());
        if (!StringTools.isEmpty(currentFileIds)) {
            query.setExcludeFileIdArray(currentFileIds.split(","));
        }
        query.setDelFlag(FileDelFlagEnums.USING.getFlag());
        query.setOrderBy("create_time desc");
        return fileInfoService.findListByParam(query);

    }

    // 移动文件到指定文件夹
    @Override
    public void changeFileFolder(String fileIds, String filePid, String userId) {
        if (fileIds.equals(filePid)) {
            //移动到自己
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        // 以filePid当做fileId加上userId查询
        if (!Constants.ZERO_STR.equals(filePid)) {
            FileInfo fileInfo = getFileInfoByFileIdAndUserId(filePid, userId);
            // 当前用户移动到的目录不存在或者不是目录
            if (fileInfo == null || !FileDelFlagEnums.USING.getFlag().equals(fileInfo.getDelFlag())) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
        }
        // fileIds -> {fileId1,fileId2,fileId3...}
        String[] fileIdArray = fileIds.split(",");

        // 如果移动到的目录正常，查询出toFile下的所有文件
        FileInfoQuery query = new FileInfoQuery();
        query.setFilePid(filePid);
        query.setUserId(userId);
        List<FileInfo> dbFileList = findListByParam(query);

        // 将查询出的list集合收集为以fileName为key, 以集合元素fileInfo为值
        // (file1, file2) -> file2) 如果两个文件名字相同，取第二个
        Map<String, FileInfo> dbFileNameMap = dbFileList.stream()
                        .collect(Collectors.toMap(FileInfo::getFileName,
                                Function.identity(),
                                (file1, file2) -> file2));
        //查询选中的文件
        query = new FileInfoQuery();
        query.setUserId(userId);
        query.setFileIdArray(fileIdArray);
        List<FileInfo> selectFileList = findListByParam(query);

        //如果存在名字相同，则将所选文件重命名
        for (FileInfo item : selectFileList) {
            FileInfo rootFileInfo = dbFileNameMap.get(item.getFileName());
            FileInfo updateInfo = new FileInfo();
            if (rootFileInfo != null) {
                //文件名已经存在，重命名被还原的文件名
                String fileName = StringTools.rename(item.getFileName());
                updateInfo.setFileName(fileName);
            }
            updateInfo.setFilePid(filePid);
            fileInfoMapper.updateByFileIdAndUserId(updateInfo, item.getFileId(), userId);
        }
    }


    private List<FileInfo> selectListByIdsAndDelFlag(String userId, String fileIds, Integer delFlag) {
        String[] fileIdArray = fileIds.split(",");
        FileInfoQuery query = new FileInfoQuery();
        query.setUserId(userId);
        query.setFileIdArray(fileIdArray);
        query.setDelFlag(delFlag);
        return fileInfoMapper.selectList(query);
    }

    // 批量将文件放入回收站
    @Override
    @Transactional
    public void removeFile2RecycleBatch(String userId, String fileIds) {
        // 查询该用户需删除的fileIds并且状态为使用中的文件
        List<FileInfo> fileInfoList = selectListByIdsAndDelFlag(userId, fileIds, FileDelFlagEnums.USING.getFlag());
        if (fileInfoList.isEmpty()) {
            return;
        }
        // 如果不为空
        List<String> delFilePidList = new ArrayList<>();
        fileInfoList.stream()
                .filter(fileInfo ->
                        fileInfo.getFolderType().equals(FileFolderTypeEnums.FOLDER.getType()))
                .forEach(fileInfo ->
                        findAllSubFolderFileIdList(delFilePidList, userId, fileInfo.getFileId(), FileDelFlagEnums.USING.getFlag()));

        //将目录下的所有文件更新为已删除
        if (!delFilePidList.isEmpty()) {
            FileInfo updateInfo = new FileInfo();
            updateInfo.setDelFlag(FileDelFlagEnums.DEL.getFlag());
            this.fileInfoMapper.updateFileDelFlagBatch(updateInfo, userId, delFilePidList,
                    null, FileDelFlagEnums.USING.getFlag());
        }

        //将选中的文件更新为回收站
        List<String> delFileIdList = Arrays.asList(fileIds.split(","));
        FileInfo fileInfo = new FileInfo();
        fileInfo.setRecoveryTime(new Date());
        fileInfo.setDelFlag(FileDelFlagEnums.RECYCLE.getFlag());
        this.fileInfoMapper.updateFileDelFlagBatch(fileInfo, userId, null,
                delFileIdList, FileDelFlagEnums.USING.getFlag());
    }

    // 回收站还原
    @Override
    @Transactional
    public void recoverFileBatch(String userId, String fileIds) {

        // 首先将选中的需还原的文件查找出来
        List<FileInfo> fileInfoList = selectListByIdsAndDelFlag(userId, fileIds, FileDelFlagEnums.RECYCLE.getFlag());

        //delFileSubFolderFileIdList为所有文件夹的id
        List<String> delFileSubFolderFileIdList = new ArrayList<>();
        //找到所选文件子目录文件ID

        // 如果是文件夹，递归找出该文件夹中的所有文件夹
        fileInfoList.stream()
                .filter(fileInfo ->
                        fileInfo.getFolderType().equals(FileFolderTypeEnums.FOLDER.getType()))
                .forEach(fileInfo ->
                        findAllSubFolderFileIdList(delFileSubFolderFileIdList, userId,
                                fileInfo.getFileId(), FileDelFlagEnums.USING.getFlag()));

        // 查询所有跟目录的文件准备判断是否需要重命名
        FileInfoQuery query = new FileInfoQuery();
        query = new FileInfoQuery();
        query.setUserId(userId);
        query.setDelFlag(FileDelFlagEnums.USING.getFlag());
        query.setFilePid(Constants.ZERO_STR);
        List<FileInfo> allRootFileList = this.fileInfoMapper.selectList(query);

        Map<String, FileInfo> rootFileMap =
                allRootFileList.stream().
                        collect(Collectors.toMap(FileInfo::getFileName, Function.identity(), (file1, file2) -> file2));

        // 将目录下的所有删除的文件更新为正常
        if (!delFileSubFolderFileIdList.isEmpty()) {
            FileInfo fileInfo = new FileInfo();
            fileInfo.setDelFlag(FileDelFlagEnums.USING.getFlag());
            this.fileInfoMapper.updateFileDelFlagBatch(fileInfo, userId, delFileSubFolderFileIdList,
                    null, FileDelFlagEnums.DEL.getFlag());
        }

        // 将选中的文件更新为正常,且父级目录到跟目录
        List<String> delFileIdList = Arrays.asList(fileIds.split(","));
        FileInfo fileInfo = new FileInfo();
        fileInfo.setDelFlag(FileDelFlagEnums.USING.getFlag());
        fileInfo.setFilePid(Constants.ZERO_STR);
        fileInfo.setLastUpdateTime(new Date());
        this.fileInfoMapper.updateFileDelFlagBatch(fileInfo, userId, null, delFileIdList,
                FileDelFlagEnums.RECYCLE.getFlag());

        //将所选文件重命名
        for (FileInfo item : fileInfoList) {
            // 从map中查找名字相同的文件
            FileInfo rootFileInfo = rootFileMap.get(item.getFileName());
            //文件名已经存在，重命名被还原的文件名
            if (rootFileInfo != null) {
                String fileName = StringTools.rename(item.getFileName());
                FileInfo updateInfo = new FileInfo();
                updateInfo.setFileName(fileName);
                this.fileInfoMapper.updateByFileIdAndUserId(updateInfo, item.getFileId(), userId);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delFileBatch(String userId, String fileIds, boolean adminOp) {

        List<FileInfo> fileInfoList = selectListByIdsAndDelFlag(userId, fileIds, FileDelFlagEnums.RECYCLE.getFlag());

        List<String> delFileSubFolderFileIdList = new ArrayList<>();
        //找到所选文件子目录文件ID
        fileInfoList.stream()
                .filter(fileInfo ->
                        fileInfo.getFolderType().equals(FileFolderTypeEnums.FOLDER.getType()))
                .forEach(fileInfo ->
                        findAllSubFolderFileIdList(delFileSubFolderFileIdList, userId,
                                fileInfo.getFileId(), FileDelFlagEnums.DEL.getFlag()));


        //删除所选文件，子目录中的文件
        if (!delFileSubFolderFileIdList.isEmpty()) {
            this.fileInfoMapper.delFileBatch(userId, delFileSubFolderFileIdList, null, adminOp
                    ? null : FileDelFlagEnums.DEL.getFlag());
        }
        //删除所选文件
        this.fileInfoMapper.delFileBatch(userId, null, Arrays.asList(fileIds.split(",")),
                adminOp ? null : FileDelFlagEnums.RECYCLE.getFlag());

        Long useSpace = this.fileInfoMapper.selectUseSpace(userId);
        UserInfo userInfo = new UserInfo();
        userInfo.setUseSpace(useSpace);
        userInfoMapper.updateByUserId(userInfo, userId);

        //设置缓存
        UserSpaceDto userSpaceDto = redisComponent.getUserSpaceUse(userId);
        userSpaceDto.setUseSpace(useSpace);
        redisComponent.saveUserSpaceUse(userId, userSpaceDto);

    }

    @Override
    public void saveShare(String shareRootFilePid, String shareFileIds, String myFolderId,
                          String shareUserId, String currentUserId) {
        String[] shareFileIdArray = shareFileIds.split(",");
        //目标目录文件列表
        FileInfoQuery fileInfoQuery = new FileInfoQuery();
        fileInfoQuery.setUserId(currentUserId);
        fileInfoQuery.setFilePid(myFolderId);
        // 目标文件夹下所有文件
        List<FileInfo> currentFileList = this.fileInfoMapper.selectList(fileInfoQuery);
        // 映射成map
        Map<String, FileInfo> currentFileMap =
                currentFileList.stream()
                        .collect(Collectors.toMap(FileInfo::getFileName, Function.identity(), (file1, file2) -> file2));
        //选择的文件
        fileInfoQuery = new FileInfoQuery();
        fileInfoQuery.setUserId(shareUserId);
        fileInfoQuery.setFileIdArray(shareFileIdArray);
        // 选中的所有文件
        List<FileInfo> shareFileList = this.fileInfoMapper.selectList(fileInfoQuery);

        //重命名选择的文件
        List<FileInfo> copyFileList = new ArrayList<>();
        Date curDate = new Date();
        for (FileInfo item : shareFileList) {
            FileInfo haveFile = currentFileMap.get(item.getFileName());
            if (haveFile != null) {
                item.setFileName(StringTools.rename(item.getFileName()));
            }
            // 需要找出所有文件复制
            findAllSubFile(copyFileList, item, shareUserId, currentUserId, curDate, myFolderId);
        }
//        System.out.println(copyFileList.size());
        this.fileInfoMapper.insertBatch(copyFileList);
    }

    @Override
    public void deleteFileByUserId(String userId) {
        this.fileInfoMapper.deleteFileByUserId(userId);
    }

    private void findAllSubFile(List<FileInfo> copyFileList, FileInfo fileInfo, String sourceUserId,
                                String currentUserId, Date curDate, String newFilePid) {
        // 将文件添加进集合
        String sourceFileId = fileInfo.getFileId();
        fileInfo.setCreateTime(curDate);
        fileInfo.setLastUpdateTime(curDate);
        fileInfo.setFilePid(newFilePid);
        fileInfo.setUserId(currentUserId);
        String newFileId = StringTools.getRandomString(Constants.LENGTH_10);
        fileInfo.setFileId(newFileId);
        copyFileList.add(fileInfo);
        // 如果是目录的话，递归添加
        if (FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
            FileInfoQuery query = new FileInfoQuery();
            query.setFilePid(sourceFileId);
            query.setUserId(sourceUserId);
            List<FileInfo> sourceFileList = this.fileInfoMapper.selectList(query);

            sourceFileList.forEach(item -> findAllSubFile(copyFileList, item, sourceUserId, currentUserId, curDate, newFileId));
        }
    }
//
//    /**
//     * 前端传参： String shareId, String filePid
//     * shareId -> shareSessionDto
//     * @param rootFilePid shareSessionDto.getFileId()
//     * @param userId shareSessionDto.getShareUserId()
//     * @param fileId filePid
//     */
//    @Override
//    public void checkRootFilePid(String rootFilePid, String userId, String fileId) {
//        if (StringTools.isEmpty(fileId)) {
//            throw new BusinessException(ResponseCodeEnum.CODE_600);
//        }
//        if (rootFilePid.equals(fileId)) {
//            return;
//        }
//        checkFilePid(rootFilePid, fileId, userId);
//    }

//    private void checkFilePid(String rootFilePid, String fileId, String userId) {
//        FileInfo fileInfo = this.fileInfoMapper.selectByFileIdAndUserId(fileId, userId);
//        if (fileInfo == null) {
//            throw new BusinessException(ResponseCodeEnum.CODE_600);
//        }
//        if (Constants.ZERO_STR.equals(fileInfo.getFilePid())) {
//            throw new BusinessException(ResponseCodeEnum.CODE_600);
//        }
//        if (fileInfo.getFilePid().equals(rootFilePid)) {
//            return;
//        }
//        checkFilePid(rootFilePid, fileInfo.getFilePid(), userId);
//    }

    // 递归查找文件夹下的所有文件
    private void findAllSubFolderFileIdList(List<String> fileIdList, String userId, String fileId, Integer delFlag) {
        // 首先将自己添加进删除集合
        fileIdList.add(fileId);

        // 然后查找自己下面的所有的文件夹
        FileInfoQuery query = new FileInfoQuery();
        query.setUserId(userId);
        query.setFilePid(fileId);
        query.setDelFlag(delFlag);
        query.setFolderType(FileFolderTypeEnums.FOLDER.getType());
        List<FileInfo> fileInfoList = this.fileInfoMapper.selectList(query);

        for (FileInfo fileInfo : fileInfoList) {
            findAllSubFolderFileIdList(fileIdList, userId, fileInfo.getFileId(), delFlag);
        }
    }

    private void checkFileName(String filePid, String userId,
                               String fileName, Integer folderType) {
        FileInfoQuery fileInfoQuery = new FileInfoQuery();
        fileInfoQuery.setFolderType(folderType);
        fileInfoQuery.setFileName(fileName);
        fileInfoQuery.setFilePid(filePid);
        fileInfoQuery.setUserId(userId);
        fileInfoQuery.setDelFlag(FileDelFlagEnums.USING.getFlag());
        Integer count = this.fileInfoMapper.selectCount(fileInfoQuery);
        if (count > 0) {
            throw new BusinessException("此目录下已存在同名文件，请修改名称");
        }
    }

    @Override
    public FileInfo rename(String fileId, String userId, String fileName) {
        FileInfo fileInfo = this.fileInfoMapper.selectByFileIdAndUserId(fileId, userId);
        if (fileInfo == null) {
            throw new BusinessException("文件不存在");
        }
        String filePid = fileInfo.getFilePid();
        checkFileName(filePid, userId, fileName, fileInfo.getFolderType());
        //文件获取后缀
        if (FileFolderTypeEnums.FILE.getType().equals(fileInfo.getFolderType())) {
            fileName = fileName + StringTools.getFileSuffix(fileInfo.getFileName());
        }
        Date curDate = new Date();
        FileInfo dbInfo = new FileInfo();
        dbInfo.setFileName(fileName);
        dbInfo.setLastUpdateTime(curDate);
        fileInfoMapper.updateByFileIdAndUserId(dbInfo, fileId, userId);

        fileInfo.setFileName(fileName);
        fileInfo.setLastUpdateTime(curDate);
        return fileInfo;
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<FileInfo> findListByPage(FileInfoQuery param) {
        // 记录条数
        int count = fileInfoMapper.selectCount(param);
        // 如果未选择页面大小则使用默认的15
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        /**
         * param.getPageNo() 第几页
         * count 共多少条记录
         * pageSize 一页显示多少页
         */
        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<FileInfo> list = fileInfoMapper.selectList(param);
        PaginationResultVO<FileInfo> result = new PaginationResultVO(count,
                page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 实现上传核心方法 uploadFile -> transferFile -> union -> 生成缩略图
     *
     * @param webUserDto 用户信息
     * @param fileId     非必传，第一个分片文件不传
     * @param file       传的文件
     * @param fileName   文件名
     * @param filePid    在哪一个目录
     * @param fileMd5    前端做的
     * @param chunkIndex 第几个分片
     * @param chunks     总共有多少个分片
     */
    @Override
    @Transactional
    public UploadResultDto uploadFile(SessionWebUserDto webUserDto, String fileId, MultipartFile file,
                                      String fileName, String filePid, String fileMd5, Integer chunkIndex, Integer chunks) {

        UploadResultDto uploadResultDto = new UploadResultDto();
        boolean uploadSuccess = true;
        File tempFileFolder = null;
        Date curDate = new Date();
        try {
            if (StringTools.isEmpty(fileId)) {
                fileId = StringTools.getRandomString(Constants.LENGTH_10);
            }
            uploadResultDto.setFileId(fileId);

            // 获取用户可用空间
            String userId = webUserDto.getUserId();
            UserSpaceDto userSpaceDto = redisComponent.getUserSpaceUse(userId);
            // 第一个文件，判断是否可秒传
            if (chunkIndex == 0) {
                // 封装查询条件
                FileInfoQuery fileInfoQuery = new FileInfoQuery();
                // 文件MD5值
                fileInfoQuery.setFileMd5(fileMd5);
                // 只取第一条
                fileInfoQuery.setSimplePage(new SimplePage(0, 1));
                // 转码成功，使用中
                fileInfoQuery.setStatus(FileStatusEnums.USING.getStatus());
                // 查询
                List<FileInfo> dbFileList = fileInfoMapper.selectList(fileInfoQuery);
                if (!dbFileList.isEmpty()) {
                    // 数据库中找到，可秒传
                    FileInfo dbFile = dbFileList.get(0);
                    // 判断文件大小
                    if (dbFile.getFileSize() + userSpaceDto.getUseSpace() > userSpaceDto.getTotalSpace()) {
                        // 空间不足
                        throw new BusinessException(ResponseCodeEnum.CODE_904);
                    }

                    dbFile.setFileId(fileId);
                    dbFile.setFilePid(filePid);
                    dbFile.setUserId(userId);
                    dbFile.setCreateTime(curDate);
                    dbFile.setLastUpdateTime(curDate);
                    dbFile.setStatus(FileStatusEnums.USING.getStatus());
                    dbFile.setDelFlag(FileDelFlagEnums.USING.getFlag());
                    dbFile.setFileMd5(fileMd5);
                    // 文件自动重命名
                    fileName = autoRename(filePid, userId, fileName);
                    dbFile.setFileName(fileName);
                    // 更新数据库中的文件信息
                    fileInfoMapper.insert(dbFile);

                    uploadResultDto.setStatus(UploadStatusEnums.UPLOAD_SECONDS.getCode());
                    // 更新用户使用空间
                    updateUserSpace(webUserDto, dbFile.getFileSize());
                    return uploadResultDto;
                }
            }

            // 判断磁盘空间（分片 + 临时 + 已使用 > 总 ? 抛异常 : 继续）
            Long currentTempSize = redisComponent.getFileTempSize(userId, fileId);
            if (file.getSize() + currentTempSize + userSpaceDto.getUseSpace() >
                    userSpaceDto.getTotalSpace()) {
                //空间不足
                throw new BusinessException(ResponseCodeEnum.CODE_904);
            }

            // 暂存临时目录 e:/webser/web_app/easypan/temp/
            String tempFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_TEMP;
            // userId + fileId
            String currentUserFolderName = webUserDto.getUserId() + fileId;

            // e:/webser/web_app/easypan/temp/{userId}/{fileId}
            tempFileFolder = new File(tempFolderName + "/" + currentUserFolderName);
            if (!tempFileFolder.exists()) {
                tempFileFolder.mkdirs();
            }

            File newFile = new File(tempFileFolder.getPath() + "/" + chunkIndex);
            file.transferTo(newFile);

            redisComponent.saveFileTempSize(userId, fileId, file.getSize());
            if (chunkIndex < chunks - 1) {
                uploadResultDto.setStatus(UploadStatusEnums.UPLOADING.getCode());
                return uploadResultDto;
            }

            // 如果是最后一个分片，上传完成，记录数据库，异步合并分片
            String month = DateUtil.format(new Date(), DateTimePatternEnum.YYYYMM.getPattern());
            String fileSuffix = StringTools.getFileSuffix(fileName);
            // 真实文件名
            // userId + fileId.fileSuffix
            String realFileName = currentUserFolderName + fileSuffix;
            // 根据后缀从枚举类中获取文件类别
            FileTypeEnums fileTypeEnums = FileTypeEnums.getFileTypeBySuffix(fileSuffix);

            //自动重命名
            fileName = autoRename(filePid, userId, fileName);
            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileId(fileId);
            fileInfo.setUserId(userId);
            fileInfo.setFileMd5(fileMd5);
            fileInfo.setFileName(fileName);
            fileInfo.setFilePath(month + "/" + realFileName);
            fileInfo.setFilePid(filePid);
            fileInfo.setCreateTime(curDate);
            fileInfo.setLastUpdateTime(curDate);
            fileInfo.setFileCategory(fileTypeEnums.getCategory().getCategory());
            fileInfo.setFileType(fileTypeEnums.getType());
            fileInfo.setStatus(FileStatusEnums.TRANSFER.getStatus());
            fileInfo.setFolderType(FileFolderTypeEnums.FILE.getType());
            fileInfo.setDelFlag(FileDelFlagEnums.USING.getFlag());
            this.fileInfoMapper.insert(fileInfo);

            Long totalSize = redisComponent.getFileTempSize(webUserDto.getUserId(), fileId);
            updateUserSpace(webUserDto, totalSize);

            uploadResultDto.setStatus(UploadStatusEnums.UPLOAD_FINISH.getCode());

            // 事务提交后执行
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileInfoService.transferFile(fileInfo.getFileId(), webUserDto);
                    redisComponent.removeFileTempSize(userId, fileInfo.getFileId());
                }
            });

        } catch (BusinessException e) {
            log.error("文件上传失败");
            uploadSuccess = false;
            throw e;
        } catch (Exception e) {
            log.error("文件上传失败");
            uploadSuccess = false;
        } finally {
            //如果上传失败，清除临时目录
            if (tempFileFolder != null && !uploadSuccess) {
                try {
                    FileUtils.deleteDirectory(tempFileFolder);
                } catch (IOException e) {
                    log.error("删除临时目录失败");
                }
            }
        }
        return uploadResultDto;

    }

    // 转码
    @Async
    public void transferFile(String fileId, SessionWebUserDto webUserDto) {
        boolean transferSuccess = true;
        String targetFilePath = null;
        String cover = null;
        FileTypeEnums fileTypeEnum = null;
        FileInfo fileInfo = fileInfoMapper.selectByFileIdAndUserId(fileId, webUserDto.getUserId());
        try {
            if (fileInfo == null || !FileStatusEnums.TRANSFER.getStatus().equals(fileInfo.getStatus())) {
                return;
            }
            //临时目录
            String tempFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_TEMP;
            String currentUserFolderName = webUserDto.getUserId() + fileId;
            File fileFolder = new File(tempFolderName + "/" + currentUserFolderName);
            if (!fileFolder.exists()) {
                fileFolder.mkdirs();
            }
            //文件后缀
            String fileSuffix = StringTools.getFileSuffix(fileInfo.getFileName());
            String month = DateUtil.format(fileInfo.getCreateTime(), DateTimePatternEnum.YYYYMM.getPattern());
            //目标目录 e:/webser/web_app/easypan + /file + /{month}
            String targetFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE;
            File targetFolder = new File(targetFolderName + "/" + month);
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }
            //真实文件名 {userId+fileId}
            String realFileName = currentUserFolderName + fileSuffix;
            //真实文件路径
            targetFilePath = targetFolder.getPath() + "/" + realFileName;
            //合并文件
            /**
             * fileFolder.getPath() 临时目录
             * targetFilePath 目标目录
             * fileInfo.getFileName() 文件名
             * delSource
             */
            union(fileFolder.getPath(), targetFilePath, fileInfo.getFileName(), true);
            fileTypeEnum = FileTypeEnums.getFileTypeBySuffix(fileSuffix);
            if (FileTypeEnums.VIDEO == fileTypeEnum) {
                //视频文件切割
                cutFile4Video(fileId, targetFilePath);
                //视频生成缩略图
                cover = month + "/" + currentUserFolderName + Constants.IMAGE_PNG_SUFFIX;
                String coverPath = targetFolderName + "/" + cover;
                ScaleFilter.createCover4Video(new File(targetFilePath), Constants.LENGTH_150, new File(coverPath));
            } else if (FileTypeEnums.IMAGE == fileTypeEnum) {
                //生成缩略图
                cover = month + "/" + realFileName.replace(".", "_.");
                String coverPath = targetFolderName + "/" + cover;
                Boolean created = ScaleFilter.createThumbnailWidthFFmpeg(new File(targetFilePath), Constants.LENGTH_150, new File(coverPath), false);
                // 如果没有生成缩略图，直接将原图复制当做缩略图
                if (!created) {
                    FileUtils.copyFile(new File(targetFilePath), new File(coverPath));
                }
            }
        } catch (Exception e) {
            log.error("文件转码失败，文件Id:{},userId:{}", fileId, webUserDto.getUserId(), e);
            transferSuccess = false;
        } finally {
            FileInfo updateInfo = new FileInfo();
            updateInfo.setFileSize(new File(targetFilePath).length());
            updateInfo.setFileCover(cover);
            updateInfo.setStatus(transferSuccess ? FileStatusEnums.USING.getStatus() : FileStatusEnums.TRANSFER_FAIL.getStatus());
            fileInfoMapper.updateFileStatusWithOldStatus(fileId, webUserDto.getUserId(), updateInfo, FileStatusEnums.TRANSFER.getStatus());
        }
    }

    public static void union(String dirPath, String toFilePath, String fileName, boolean delSource)
            throws BusinessException {
        // 目录不存在，直接抛异常
        File dir = new File(dirPath);
        if (!dir.exists()) {
            throw new BusinessException("目录不存在");
        }
        // 取出temp目录下的所有文件
        File[] fileList = dir.listFiles();
        File targetFile = new File(toFilePath);
        // RandomAccessFile支持"随机访问"的方式，程序可以直接跳转到文件的任意地方来读写数据。
        RandomAccessFile writeFile = null;
        try {
            // 目标目录
            writeFile = new RandomAccessFile(targetFile, "rw");
            byte[] b = new byte[1024 * 10];
            for (int i = 0; i < fileList.length; i++) {
                int len = -1;
                //创建读块文件的对象，分别取出 0, 1, 2 ...
                File chunkFile = new File(dirPath + File.separator + i);
                RandomAccessFile readFile = null;
                try {
                    readFile = new RandomAccessFile(chunkFile, "r");
                    while ((len = readFile.read(b)) != -1) {
                        writeFile.write(b, 0, len);
                    }
                } catch (Exception e) {
                    log.error("合并分片失败", e);
                    throw new BusinessException("合并文件失败");
                } finally {
                    if (readFile != null) {
                        readFile.close();
                    }
                }
            }
        } catch (Exception e) {
            log.error("合并文件:{}失败", fileName, e);
            throw new BusinessException("合并文件" + fileName + "出错了");
        } finally {
            try {
                if (writeFile != null) {
                    writeFile.close();
                }
            } catch (IOException e) {
                log.error("关闭流失败", e);
            }
            if (delSource) {
                if (dir.exists()) {
                    try {
                        // 以递归方式删除目录。
                        FileUtils.deleteDirectory(dir);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    // 利用java代码操作命令行窗口执行FFmpeg对视频进行切割，生成.m3u8索引文件和.ts切片文件

    private void cutFile4Video(String fileId, String videoFilePath) {
        //创建同名切片目录
        File tsFolder = new File(videoFilePath.substring(0, videoFilePath.lastIndexOf(".")));
        if (!tsFolder.exists()) {
            tsFolder.mkdirs();
        }
        final String CMD_TRANSFER_2TS = "ffmpeg -y -i %s  -vcodec copy -acodec copy -vbsf h264_mp4toannexb %s";
        final String CMD_CUT_TS = "ffmpeg -i %s -c copy -map 0 -f segment -segment_list %s -segment_time 30 %s/%s_%%4d.ts";

        String tsPath = tsFolder + "/" + Constants.TS_NAME;
        //生成index.ts
        String cmd = String.format(CMD_TRANSFER_2TS, videoFilePath, tsPath);
        ProcessUtils.executeCommand(cmd, false);
        //生成索引文件.m3u8 和切片.ts
        cmd = String.format(CMD_CUT_TS, tsPath, tsFolder.getPath() + "/" + Constants.M3U8_NAME, tsFolder.getPath(), fileId);
        ProcessUtils.executeCommand(cmd, false);
        //删除index.ts
        new File(tsPath).delete();
    }

    private void updateUserSpace(SessionWebUserDto webUserDto, Long useSpace) {
        Integer count = userInfoMapper.updateUserSpace(webUserDto.getUserId(),
                useSpace, null);
        // 更新失败，使用空间超过总空间
        if (count == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_904);
        }
        // 更新成功，同时更新redis中的缓存
        String userId = webUserDto.getUserId();
        UserSpaceDto spaceDto = redisComponent.getUserSpaceUse(userId);
        spaceDto.setUseSpace(spaceDto.getUseSpace() + useSpace);
        redisComponent.saveUserSpaceUse(userId, spaceDto);

    }

    private String autoRename(String filePid, String userId, String fileName) {
        // 封装查询条件，如果查询出数据，则需要重命名
        FileInfoQuery fileInfoQuery = new FileInfoQuery();
        fileInfoQuery.setUserId(userId);
        fileInfoQuery.setFilePid(filePid);
        fileInfoQuery.setDelFlag(FileDelFlagEnums.USING.getFlag());
        fileInfoQuery.setFileName(fileName);
        Integer count = this.fileInfoMapper.selectCount(fileInfoQuery);
        if (count > 0) {
            return StringTools.rename(fileName);
        }

        return fileName;
    }


}