package com.easypan.service;

import com.easypan.entity.dto.SessionWebUserDto;
import com.easypan.entity.dto.UploadResultDto;
import com.easypan.entity.po.FileInfo;
import com.easypan.entity.query.FileInfoQuery;
import com.easypan.entity.vo.PaginationResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


/**
 * 文件信息 业务接口
 */
public interface FileInfoService {

    FileInfo rename(String fileId, String userId, String fileName);


    PaginationResultVO<FileInfo> findListByPage(FileInfoQuery param);


    UploadResultDto uploadFile(SessionWebUserDto webUserDto, String fileId, MultipartFile file,
                               String fileName, String filePid, String fileMd5, Integer chunkIndex, Integer chunks);


    FileInfo getFileInfoByFileIdAndUserId(String realFileId, String userId);

    FileInfo newFolder(String filePid, String userId, String fileName);

    List<FileInfo> findListByParam(FileInfoQuery param);

    List<FileInfo> loadAllFolder(String userId, String filePid, String currentFileIds);

    void changeFileFolder(String fileIds, String filePid, String userId);

    void removeFile2RecycleBatch(String userId, String fileIds);

    void recoverFileBatch(String userId, String fileIds);

    void delFileBatch(String userId, String fileIds, boolean b);

    void saveShare(String shareRootFilePid, String shareFileIds, String myFolderId, String shareUserId, String currentUserId);

    void deleteFileByUserId(String userId);

}