package server.controllers.rest;

import static server.controllers.rest.response.BaseResponse.Status.BAD_DATA;
import static server.controllers.rest.response.BaseResponse.Status.OK;
import static server.controllers.rest.response.CannedResponse.INVALID_SESSION;
import com.google.common.hash.Hashing;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import server.controllers.FuseSessionController;
import server.controllers.rest.response.BaseResponse;
import server.controllers.rest.response.GeneralResponse;
import server.controllers.rest.response.TypedResponse;
import server.entities.dto.FuseSession;
import server.entities.dto.UploadFile;
import server.entities.dto.user.User;
import server.repositories.FileRepository;
import server.repositories.group.FileDownloadRepository;
import server.utility.ImageResizer;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping(value = "/files")
@Api(value = "File Endpoints")
public class FileController {
  @Autowired
  private FuseSessionController fuseSessionController;

  @Autowired
  private FileRepository fileRepository;

  @Autowired
  private FileDownloadRepository fileDownloadRepository;

  @Value("${fuse.fileUploadPath}")
  private String fileUploadPath;


  private Logger logger = LoggerFactory.getLogger(FileController.class);

  @PostMapping(path = "/upload")
  @ResponseBody
  @ApiOperation(value = "Uploads a new file",
      notes = "Max file size is 5MB")
  public TypedResponse<UploadFile> fileUpload(
      @ApiParam(value = "file to upload")
      @RequestParam("file") MultipartFile fileToUpload,
      HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, BaseResponse.Status.DENIED, errors);
    }
    User currentUser = session.get().getUser();
    if (fileToUpload != null) {
      if (fileToUpload.getSize() > 0 && fileToUpload.getName().equals("file")) {
        UploadFile savedResult;
        try {
          savedResult = saveFile(fileToUpload, "", currentUser);
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
          return new TypedResponse<>(response, BaseResponse.Status.ERROR, e.getMessage());
        }
        return new TypedResponse<>(response, OK, null, savedResult);
      }
    }
    return new TypedResponse<>(response, BAD_DATA, "Invalid file, unable to save");
  }

  @GetMapping(path = "/download/{id}")
  @ResponseBody
  @ApiOperation(value = "Downloads a file",
      notes = "Will download as an attachment")
  public ResponseEntity<Resource> fileDownload(
      @ApiParam(value = "file id to download")
      @PathVariable(value = "id") Long id, HttpServletResponse response,
      HttpServletRequest request) throws Exception {

    UploadFile fileToFind = fileDownloadRepository.findOne(id);
    if (fileToFind == null) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return null;
    }
    String originalFileName = fileToFind.getFileName();

    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + originalFileName);
    String fileName = fileToFind.getHash() + "." + fileToFind.getUpload_time().getTime() + "." + fileToFind.getUser().getId();
    Path path = Paths.get(fileUploadPath, fileName);
    ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(path));
    File file = new File(fileUploadPath, fileName);

    return ResponseEntity.ok()
        .headers(headers)
        .contentLength(file.length())
        .contentType(MediaType.parseMediaType("application/octet-stream")).body(resource);
  }

  @ApiOperation(value = "Get user's files")
  @GetMapping
  @ResponseBody
  public TypedResponse<Iterable<UploadFile>> getFiles(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, GeneralResponse.Status.DENIED, errors);
    }

    return new TypedResponse<>(response, OK, null, fileRepository.getUploadedFiles(session.get().getUser()));
  }

  public UploadFile saveFile(MultipartFile fileToUpload, String type, User currentUser) throws Exception {
    UploadFile uploadFile = new UploadFile();
    String hash = Hashing.sha256()
        .hashString(fileToUpload.getOriginalFilename(), StandardCharsets.UTF_8)
        .toString();
    Timestamp ts = new Timestamp(System.currentTimeMillis());
    long timestamp = ((ts.getTime()) / 1000) * 1000;
    String fileName = hash + "." + timestamp + "." + currentUser.getId().toString();
    File fileToSave = new File(fileUploadPath, fileName);
    if (!fileToSave.createNewFile()) {
      // should delete image and retry
      throw new Exception("Could not save image");
    }
    String[] fileType = fileToUpload.getContentType().split("/");
    fileToUpload.transferTo(fileToSave);

    String path = fileUploadPath + "/" + fileName;

    BufferedImage originalImage = ImageIO.read(new File(path));

    BufferedImage resizedImage;
    if (fileType[0].equals("image")) {
      switch (type) {
        case "avatar":
          resizedImage = ImageResizer.resizeToAvatarImage(originalImage);
          break;
        case "background":
          resizedImage = ImageResizer.resizeToBackgroundImage(originalImage);
          break;
        default:
          resizedImage = originalImage;
      }
      ImageIO.write(resizedImage, fileType[1], new File(path));
    }

    Long size = new File(path).length();
    uploadFile.setHash(hash);
    uploadFile.setUpload_time(new Timestamp(timestamp));
    uploadFile.setFile_size(size);
    uploadFile.setFileName(fileToUpload.getOriginalFilename());
    uploadFile.setMime_type(fileToUpload.getContentType());
    uploadFile.setUser(currentUser);

    return fileRepository.save(uploadFile);
  }
}
