package server.controllers.rest.group;

import static server.constants.InvitationStatus.PENDING;
import static server.constants.RoleValue.ADMIN;
import static server.constants.RoleValue.DEFAULT_USER;
import static server.constants.RoleValue.INVITED_TO_INTERVIEW;
import static server.constants.RoleValue.INVITED_TO_JOIN;
import static server.constants.RoleValue.OWNER;
import static server.controllers.rest.response.BaseResponse.Status.BAD_DATA;
import static server.controllers.rest.response.BaseResponse.Status.DENIED;
import static server.controllers.rest.response.BaseResponse.Status.ERROR;
import static server.controllers.rest.response.BaseResponse.Status.OK;
import static server.controllers.rest.response.CannedResponse.*;
import static server.utility.JoinPermissionsUtil.genericSetJoinPermissions;
import static server.utility.PagingUtil.getPagedResults;
import static server.utility.RolesUtility.getRoleFromInvitationType;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import server.controllers.FuseSessionController;
import server.controllers.rest.FileController;
import server.controllers.rest.LinkController;
import server.controllers.rest.NotificationController;
import server.controllers.rest.response.BaseResponse;
import server.controllers.rest.response.CannedResponse;
import server.controllers.rest.response.GeneralResponse;
import server.controllers.rest.response.TypedResponse;
import server.entities.MemberRelationship;
import server.entities.PossibleError;
import server.entities.dto.FuseSession;
import server.entities.dto.Link;
import server.entities.dto.UploadFile;
import server.entities.dto.group.Group;
import server.entities.dto.group.GroupApplication;
import server.entities.dto.group.GroupInvitation;
import server.entities.dto.group.GroupMember;
import server.entities.dto.group.GroupProfile;
import server.entities.dto.group.interview.Interview;
import server.entities.dto.user.User;
import server.entities.user_to_group.permissions.UserToGroupPermission;
import server.handlers.InterviewHelper;
import server.repositories.UserRepository;
import server.repositories.group.GroupApplicantRepository;
import server.repositories.group.GroupInvitationRepository;
import server.repositories.group.GroupMemberRepository;
import server.repositories.group.GroupProfileRepository;
import server.repositories.group.GroupRepository;
import server.repositories.group.InterviewRepository;
import server.utility.ApplicantUtil;
import server.utility.ElasticsearchClient;
import server.utility.InterviewUtil;
import server.utility.UserFindHelper;
import springfox.documentation.annotations.ApiIgnore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@SuppressWarnings("unused")
public abstract class GroupController<T extends Group, R extends GroupMember<T>, I extends GroupInvitation<T>> {

  @Autowired
  protected FuseSessionController fuseSessionController;

  @Autowired
  private LinkController linkController;

  @Autowired
  protected UserRepository userRepository;

  @Autowired
  private FileController fileController;

  @Autowired
  private NotificationController notificationController;

  @Autowired
  private InterviewRepository interviewRepository;

  @Autowired
  private InterviewHelper interviewHelper;

  @Autowired
  private UserFindHelper userFindHelper;

  @Autowired
  private GroupProfileRepository groupProfileRepository;

  @Autowired
  private SessionFactory sessionFactory;

  private Logger logger = LoggerFactory.getLogger(GroupController.class);

  @PostMapping
  @ResponseBody
  @ApiOperation("Create a new entity")
  public synchronized TypedResponse<Group> create(
          @ApiParam("Entity information")
          @RequestBody T entity, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);

    PossibleError possibleError = fuseSessionController.validateSession(request);
    if (possibleError.hasError()) {
      return new TypedResponse<>(response, possibleError);
    }

    if (!validFieldsForCreate(entity)) {
      errors.add(INVALID_FIELDS_FOR_CREATE);
      return new TypedResponse<>(response, errors);
    }

    if (entity.getProfile() == null) {
      errors.add("Missing profile information!");
      return new TypedResponse<>(response, errors);
    }

    User user = session.get().getUser();

    PossibleError possibleValidationError = validateGroup(user, entity);

    if (possibleValidationError.hasError()) {
      return new TypedResponse<>(response, possibleValidationError);
    }

    List<T> entities = getGroupsWith(user, entity);
    entity.setOwner(user);

    if (entities.size() == 0) {
      entity.setDeleted(false);
      Group savedEntity = getGroupRepository().save(entity);
      addRelationship(user, entity, OWNER);
      addRelationship(user, entity, ADMIN);
      createInterviewTemplate(entity);
      savedEntity.indexAsync();
      return new TypedResponse<>(response, OK, null, savedEntity);
    } else {
      errors.add("entity name already exists for user");
      return new TypedResponse<>(response, errors);
    }
  }



  @CrossOrigin
  @PutMapping(path = "/delete/{id}")
  @ResponseBody
  @ApiOperation("Delete an entity")
  public GeneralResponse delete(
          @ApiParam("ID of the entity to delete")
          @PathVariable(value = "id") Long id,
          HttpServletRequest request,
          HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    T group = getGroupRepository().findOne(id);

    if (group == null || group.getDeleted()) {
      errors.add("Entity does not exist!");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User user = session.get().getUser();
    if (!Objects.equals(group.getOwner().getId(), user.getId())) {
      errors.add("Unable to delete entity, permission denied");
      return new GeneralResponse(response, BaseResponse.Status.DENIED, errors);
    }

    group.setDeleted(true);
    getGroupRepository().save(group);
    ElasticsearchClient.instance().indexAsync(group);
    return new GeneralResponse(response);
  }

  private GeneralResponse generalApply(Long id, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, BaseResponse.Status.DENIED, errors);
    }

    GroupApplication<T> application = getApplication();
    T group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add(NO_GROUP_FOUND);
      return new GeneralResponse(response, BaseResponse.Status.BAD_DATA, errors);
    }

    application.setGroup(group);

    switch (getUserToGroupPermission(session.get().getUser(), application.getGroup()).canJoin()) {
      case ALREADY_JOINED:
        errors.add(ALREADY_JOINED_MSG);
        return new GeneralResponse(response, ERROR, errors);
    }
    List<GroupApplication> applicants = getGroupApplicantRepository().getApplicantsBySender(session.get().getUser());
    if (ApplicantUtil.applicantsContainId(applicants, id)) {
      return new GeneralResponse(response, ERROR, "Already applied");
    }
    application.setSender(session.get().getUser());
    application.setStatus(PENDING);

    ZonedDateTime now = ZonedDateTime.now();
    application.setTime(now.toString());
    getGroupApplicantRepository().save(application);

    try {
      try {
        notificationController.sendUserAppliedNotification(application);
      } catch (Exception e) {
        logger.error(e.getMessage(), e);
      }
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }

    Map<String, Object> result = new HashMap<>();
    result.put("applied", true);
    return new GeneralResponse(response, BaseResponse.Status.OK, errors, result);
  }

  @CrossOrigin
  @PutMapping(path = "/{id}")
  @ApiOperation("Updates the specified entity")
  @ResponseBody
  public BaseResponse updateGroup(
          @ApiParam("The ID of the entity to update")
          @PathVariable(value = "id") long id,
          @ApiParam("The new data for the entity")
          @RequestBody T groupData, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    User user = session.get().getUser();

    T groupToSave = getGroupRepository().findOne(id);
    if (groupToSave == null || groupToSave.getDeleted()) {
      errors.add(NO_GROUP_FOUND);
      return new GeneralResponse(response, BaseResponse.Status.BAD_DATA, errors);
    }

    UserToGroupPermission permission = getUserToGroupPermission(user, groupToSave);
    boolean canUpdate = permission.canUpdate();
    if (!canUpdate) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new GeneralResponse(response, DENIED, errors);
    }

    // Merging instead of direct copying ensures we're very clear about what can be edited, and it provides easy checks
    if (groupData.getName() != null) {
      groupToSave.setName(groupData.getName());
    }

    if (groupData.getProfile() != null) {
      if (groupToSave.getProfile() == null) {
        groupData.getProfile().setGroup(groupToSave);
        GroupProfile profile = saveProfile(groupData);
        groupToSave.setProfile(profile);
      } else {
        groupToSave.setProfile(groupToSave.getProfile().merge(groupToSave.getProfile(), groupData.getProfile()));
      }
    }

    if (groupData.getRestrictionString() != null) {
      groupToSave.setRestriction(groupData.getRestrictionString());
    }
    getGroupRepository().save(groupToSave).indexAsync();
    return new GeneralResponse(response, OK);
  }

  @PostMapping(path = "/{id}/join")
  @ApiOperation("Join the group as the current user or applies if application is needed first")
  @ResponseBody
  protected synchronized GeneralResponse join(
          @ApiParam("The id of the group to join")
          @PathVariable("id") Long id, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    T group = getGroupRepository().findOne(id);
    if(group == null || group.getDeleted())
    {
      errors.add(NO_GROUP_FOUND);
      return new GeneralResponse(response, BaseResponse.Status.BAD_DATA, errors);
    }
    User user = session.get().getUser();

    switch (getUserToGroupPermission(user, group).canJoin()) {
      case OK:
        try {
          notificationController.sendUserJoinedNotification(user, group);
          addRelationship(user, group, DEFAULT_USER);
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
          errors.add(e.getMessage());
          return new GeneralResponse(response, ERROR, errors);
        }
        return new GeneralResponse(response);
      case HAS_INVITE:
        try {
          notificationController.sendUserJoinedNotification(user, group);
          addRelationship(user, group, DEFAULT_USER);
          removeRelationship(user, group, INVITED_TO_JOIN);
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
          errors.add(e.getMessage());
          return new GeneralResponse(response, ERROR, errors);
        }
        return new GeneralResponse(response);
      case NEED_INVITE:
        // Apply if an invite is needed
        return generalApply(id, request, response);
      case ALREADY_JOINED:
        errors.add(ALREADY_JOINED_MSG);
        return new GeneralResponse(response, ERROR, errors);
      case NOT_ALLOWED:
        errors.add(NOT_ALLOWED_MSG);
        return new GeneralResponse(response, ERROR, errors);
      case ERROR:
      default:
        errors.add(SERVER_ERROR);
        return new GeneralResponse(response, ERROR, errors);
    }
  }

  private GeneralResponse generalInvite(I groupInvitation, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    User sessionUser = session.get().getUser();
    T group = groupInvitation.getGroup();
    UserToGroupPermission senderPermission = getUserToGroupPermission(sessionUser, group);
    if (!senderPermission.canInvite()) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new GeneralResponse(response, DENIED, errors);
    }

    Optional<User> receiver = userFindHelper.findUserByEmailIfIdNotSet(groupInvitation.getReceiver());

    if (!receiver.isPresent() || !userRepository.exists(receiver.get().getId())) {
      errors.add(INVALID_FIELDS_FOR_CREATE);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    UserToGroupPermission receiverPermission = getUserToGroupPermission(receiver.get(), group);

    Optional<Integer> role = getRoleFromInvitationType(groupInvitation.getType());

    if (!role.isPresent()) {
      errors.add("Unrecognized type");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    if (receiverPermission.isMember() || receiverPermission.hasRole(role.get())) {
      errors.add(ALREADY_JOINED_OR_INVITED);
      return new GeneralResponse(response, DENIED, errors);
    }

    errors = setInviteFieldsAndSave(groupInvitation, sessionUser, role.get(), receiver.get(), group);
    return new GeneralResponse(response, errors);
  }

  private List<String> setInviteFieldsAndSave(I groupInvitation, User sessionUser, Integer role, User receiver, T group) {
    List<String> errors = new ArrayList<>();

    groupInvitation.setStatus(PENDING);
    groupInvitation.setSender(sessionUser);
    switch (role) {
      case INVITED_TO_JOIN:
        addRelationship(receiver, group, INVITED_TO_JOIN);
        saveInvitation(groupInvitation);
        break;
      case INVITED_TO_INTERVIEW:
        LocalDateTime currentDateTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();
        List<Interview> availableInterviewsAfterDate = interviewRepository.getAvailableInterviewsAfterDate(group.getId(), group.getGroupType(), currentDateTime);
        if (availableInterviewsAfterDate.size() == 0) {
          errors.add(INTERVIEW_NOT_AVAILABLE);
          return errors;
        }

        addRelationship(receiver, group, INVITED_TO_INTERVIEW);
        saveInvitation(groupInvitation);
        break;
    }

    return errors;
  }

  @PostMapping(path = "/{id}/invite/{applicant_id}/{type}")
  @ResponseBody
  public GeneralResponse invite(@PathVariable("id") Long id,
                                @PathVariable("applicant_id") Long applicantId,
                                @PathVariable("type") String inviteType,
                                HttpServletRequest request, HttpServletResponse response) {
    I invite = getInvitation();
    List<String> errors = new ArrayList<>();
    invite.setGroup(getGroupRepository().findOne(id));
    GroupApplicantRepository applicantRepository = getGroupApplicantRepository();
    GroupApplication<T> applicant = (GroupApplication) applicantRepository.findOne(applicantId);
    if (applicant == null) {
      errors.add("Applicant not found");
      return new GeneralResponse(response, errors);
    }
    invite.setApplicant(applicant);
    invite.setReceiver(applicant.getSender());
    invite.setType(inviteType);
    return generalInvite(invite, request, response);
  }


  @ApiOperation(value = "Add a new interview slot", notes = "This creates a new interview slot that can be used when scheduling interviews.")
  @PostMapping(path = "/{id}/interview_slots/add")
  @ResponseBody
  public BaseResponse addInterviewSlots(
          @ApiParam("The ID of the group to add the slot to")
          @PathVariable("id") long id,
          @ApiParam("An array of interview slots to add")
          @RequestBody List<Interview> interviews, HttpServletRequest request,
          HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    if (!isValidInterviewSlots(interviews, session.get().getUser(), id)) {
      errors.add(INVALID_FIELDS);
      return new GeneralResponse(response, errors);
    }

    T group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add("Group not found!");
      return new TypedResponse<>(response, BaseResponse.Status.BAD_DATA, errors);
    }
    interviewHelper.saveNewInterviewsForGroup(interviews, group);
    return new GeneralResponse(response, OK);
  }

  @ApiOperation("Returns the available interview slots")
  @GetMapping(path = "/{id}/interview_slots/available")
  @ResponseBody
  public TypedResponse<List<Interview>> getAvailableInterviews(
          @ApiParam("ID of the group to get the interview slots for")
          @PathVariable("id") long id, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();
    Group group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add("Group not found!");
      return new TypedResponse<>(response, BaseResponse.Status.BAD_DATA, errors);
    }
    LocalDateTime currentDateTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

    List<Interview> availableInterviewsAfterDate =
            interviewRepository.getAvailableInterviewsAfterDate(id, group.getGroupType(), currentDateTime);

    return new TypedResponse<>(response, OK, new ArrayList<>(), availableInterviewsAfterDate);
  }

  @CrossOrigin
  @ApiOperation(value = "Delete a interview slot", notes = "This endpoint is used to delete interview slot.")
  @PutMapping(path = "/{id}/interview_slots/delete")
  @ResponseBody
  public BaseResponse deleteInterviewSlots(
          @ApiParam("The ID of the interview to delete")
          @PathVariable("id") long id,
          HttpServletRequest request,
          HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }
    Interview interview = interviewRepository.findOne(id);
    if (interview == null) {
      errors.add(INVALID_FIELDS);
      return new GeneralResponse(response, BAD_DATA, errors);
    }
    interview.setDeleted(true);
    interviewRepository.save(interview);
    return new GeneralResponse(response, OK);
  }

  @CrossOrigin
  @ApiOperation(value = "Edit a interview slot", notes = "This endpoint is used to edit interview slot, user can only edit time.")
  @PutMapping(path = "/{id}/interview_slots/edit")
  @ResponseBody
  public BaseResponse editInterviewSlots(
          @ApiParam("The ID of the interview to delete")
          @PathVariable("id") long id,
          @ApiParam("Interview object")
          @RequestBody Interview interview,
          HttpServletRequest request,
          HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }
    Interview interviewToSave = interviewRepository.findOne(id);
    if (interviewToSave == null) {
      errors.add(INVALID_FIELDS);
      return new GeneralResponse(response, BAD_DATA, errors);
    }
    if (interview.getStart() != null && interview.getEnd() != null) {
      ZonedDateTime startZonedDateTime = ZonedDateTime.parse(interview.getStart());
      LocalDateTime startDateTime = startZonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
      ZonedDateTime endZonedDateTime = ZonedDateTime.parse(interview.getEnd());
      LocalDateTime endDateTime = endZonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
      if (endDateTime.isBefore(startDateTime)) {
        errors.add("Invalid time");
        return new GeneralResponse(response, DENIED, errors);
      }
      interview.setStart(interview.getStart());
      interview.setEnd(interview.getEnd());
    } else {
      if (interview.getStart() != null) {
        ZonedDateTime startZonedDateTime = ZonedDateTime.parse(interview.getStart());
        LocalDateTime startDateTime = startZonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if (startDateTime.isAfter(interview.getEndDateTime())) {
          errors.add("Invalid time");
          return new GeneralResponse(response, DENIED, errors);
        }
        interview.setStart(interview.getStart());
      }

      if (interview.getEnd() != null) {
        ZonedDateTime endZonedDateTime = ZonedDateTime.parse(interview.getEnd());
        LocalDateTime endDateTime = endZonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if (endDateTime.isBefore(interview.getStartDateTime())) {
          errors.add("Invalid time");
          return new GeneralResponse(response, DENIED, errors);
        }
        interview.setEnd(interview.getEnd());
      }
    }
    interviewRepository.save(interviewToSave);
    return new GeneralResponse(response, OK);
  }


  @CrossOrigin
  @ApiOperation(value = "Updates the links for a group (must be logged in as an admin)")
  @PutMapping(path = "/{id}/links")
  @ResponseBody
  public BaseResponse updateGroupLinks(
      @ApiParam(value = "ID of the group to update")
      @PathVariable long id,
      @ApiParam(value = "Set of links for the group")
      @RequestBody List<Link> links,
      HttpServletRequest request,
      HttpServletResponse response
  ) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, BaseResponse.Status.DENIED, errors);
    }

    T group = getGroupRepository().findOne(id);

    if (group == null || group.getDeleted()) {
      errors.add("Group not found!");
      return new TypedResponse<>(response, BaseResponse.Status.BAD_DATA, errors);
    }

    User currentUser = session.get().getUser();

    if (!getUserToGroupPermissionTyped(currentUser, group).canUpdate()) {
      errors.add("Unable to edit group, permission denied");
      return new GeneralResponse(response, BaseResponse.Status.DENIED, errors);
    }

    final long profileId = group.getProfile().getId();
    String referenceType = group.getGroupType();

    return linkController.updateLinksFor(referenceType, profileId, links, response);
  }

  @ApiOperation(value = "Gets links for the group")
  @GetMapping(path = "/{id}/links")
  @ResponseBody
  public TypedResponse<List<Link>> getLinks(
      @ApiParam(value = "Id of the group to get links for")
      @PathVariable long id,
      HttpServletRequest request,
      HttpServletResponse response
  ) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, BaseResponse.Status.DENIED, errors);
    }

    T group = getGroupRepository().findOne(id);

    if (group == null || group.getDeleted()) {
      errors.add("Group not found!");
      return new TypedResponse<>(response, BaseResponse.Status.BAD_DATA, errors);
    }

    if (group.getProfile() == null) {
      return new TypedResponse<>(response, BaseResponse.Status.OK, null, new ArrayList<>());
    }

    String referenceType = group.getGroupType();

    return linkController.getLinksFor(referenceType, group.getProfile().getId(), response);
  }

  @ApiOperation("Find a group by the name and/or owner email")
  @GetMapping(path = "/find", params = {"name", "email"})
  @ResponseBody
  public TypedResponse<T> findByNameAndOwner(
          @ApiParam("Name of the group to get")
          @RequestParam(value = "name") String name,
          @ApiParam("Email address of the owner")
          @RequestParam(value = "email") String email,
          HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    User user = new User();
    user.setEmail(email);
    Optional<User> userOptional = userFindHelper.findUserByEmailIfIdNotSet(user);
    if (!userOptional.isPresent() || name == null) {
      errors.add(INVALID_FIELDS);
      return new TypedResponse<>(response, BAD_DATA, errors);
    }

    T group = createGroup();
    group.setName(name);

    List<T> matching = getGroupsWith(userOptional.get(), group);
    if (matching.size() == 0) {
      errors.add(CannedResponse.NO_GROUP_FOUND);
      return new TypedResponse<>(response, errors);
    }

    return new TypedResponse<>(response, OK, null, matching.get(0));
  }

  protected abstract T createGroup();

  @ApiOperation("Revokes access for a user")
  @PostMapping(path = "/{id}/members/{member_id}/kick")
  @ResponseBody
  public BaseResponse kickMemberAccess(
          @ApiParam("The id of the group to grant access for")
          @PathVariable(value = "id") Long id,
          @ApiParam("The id of the user to grant access to")
          @PathVariable(value = "member_id") Long memberId,
          HttpServletRequest request, HttpServletResponse response
  ) {
    return kickMember(id, memberId, ADMIN, response, request);
  }

  @ApiOperation("Grant admin access for a user")
  @PostMapping(path = "/{id}/members/{member_id}/grant/admin")
  @ResponseBody
  public BaseResponse grantAdminAccess(
          @ApiParam("The id of the group to grant access for")
          @PathVariable(value = "id") Long id,
          @ApiParam("The id of the user to grant access to")
          @PathVariable(value = "member_id") Long memberId,
          HttpServletRequest request, HttpServletResponse response
  ) {
    return grantAccessForMember(id, memberId, ADMIN, response, request);
  }

  @ApiOperation("Grant admin access for a user")
  @PostMapping(path = "/{id}/members/{member_id}/revoke/admin")
  @ResponseBody
  public BaseResponse revokeAdminAccess(
          @ApiParam("The id of the group to grant access for")
          @PathVariable(value = "id") Long id,
          @ApiParam("The id of the user to grant access to")
          @PathVariable(value = "member_id") Long memberId,
          HttpServletRequest request, HttpServletResponse response
  ) {
    return revokeAccessForMember(id, memberId, ADMIN, response, request);
  }

  @ApiOperation("Gets the members for the group")
  @GetMapping(path = "/{id}/members")
  @ResponseBody
  public TypedResponse<List<MemberRelationship>> getMembersOfGroup(
          @ApiParam("The id of the group to get the members for")
          @PathVariable(value = "id") Long id,
          @ApiParam(value = "The page of results to pull")
          @RequestParam(value = "page", required = false, defaultValue = "0") int page,
          @ApiParam(value = "The number of results per page")
          @RequestParam(value = "size", required = false, defaultValue = "15") int pageSize,
          HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();
    T group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add("Group not found!");
      return new TypedResponse<>(response, BaseResponse.Status.BAD_DATA, errors);
    }

    List<User> allMembers = new ArrayList<>(getMembersOf(group));

    List<MemberRelationship> memberRelationships = getPagedResults(allMembers, page, pageSize).stream()
            .map(user -> getUserToGroupPermission(user, group))
            .filter(UserToGroupPermission::isMember)
            .map(UserToGroupPermission::toRelationship)
            .collect(Collectors.toList());

    return new TypedResponse<>(response, BaseResponse.Status.OK, null, memberRelationships);
  }

  @GetMapping
  @ResponseBody
  @ApiOperation("Gets all of the groups of this type")
  protected TypedResponse<Iterable<T>> getAll(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, DENIED, errors);
    }
    User user = session.get().getUser();

    return new TypedResponse<>(response, OK, null,
            StreamSupport.stream(getGroupRepository().findAll().spliterator(), false)
                    .map(item -> this.setJoinPermissions(user, item))
                    .collect(Collectors.toList())
    );
  }

  @GetMapping(path = "/{id}")
  @ApiOperation("Gets the group entity by id")
  @ResponseBody
  protected TypedResponse<T> getById(
          @ApiParam("ID of the gruop to get the id for")
          @PathVariable(value = "id") Long id, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, DENIED, errors);
    }

    T group = getGroupRepository().findOne(id);

    if (group != null && !group.getDeleted()) {
      User user = session.get().getUser();
      group = setJoinPermissions(user, group);

      return new TypedResponse<>(response, OK, null, group);
    }
    errors.add("Invalid ID! Object does not exist!");

    return new TypedResponse<>(response, BAD_DATA, errors);
  }

  @GetMapping(path = "/{id}/applicants/{status}")
  @ApiOperation("Get applicants by status")
  @ResponseBody
  public TypedResponse<List<GroupApplication>> getApplicants(@ApiParam("ID of entity")
                                                             @PathVariable(value = "id")
                                                                 Long id,
                                                             @ApiParam("Applicant status (one of 'accepted', 'declined', 'pending' 'interviewed', 'interview_scheduled')")
                                                             @PathVariable(value = "status")
                                                                 String status,
                                                             HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, BaseResponse.Status.DENIED, errors);
    }

    if (GroupApplication.ValidStatuses().indexOf(status) == -1) {
      errors.add("Invalid status!");
      return new TypedResponse<>(response, BaseResponse.Status.BAD_DATA, errors);
    }

    UserToGroupPermission permission = getUserToGroupPermission(session.get().getUser(), getGroupRepository().findOne(id));
    boolean canUpdate = permission.canUpdate();
    if (!canUpdate) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new TypedResponse<>(response, DENIED, errors);
    }

    GroupApplicantRepository groupApplicantRepository = getGroupApplicantRepository();
    List<GroupApplication> applicants = groupApplicantRepository.getApplicants(getGroupRepository().findOne(id), status);

    if (status.equals("interview_scheduled")) {
      List<GroupApplication> applicantsTmp = applicants.stream()
          .filter(a -> a.getDateTime() != null)
          .sorted(Comparator.comparing(GroupApplication::getDateTime))
          .collect(Collectors.toList());
      applicantsTmp.addAll(
          applicants
              .stream()
              .filter(a -> a.getDateTime() == null)
              .collect(Collectors.toList())
      );
      applicants = applicantsTmp;
    }

    return new TypedResponse<>(response, OK, null, applicants);
  }

  @CrossOrigin
  @PutMapping(path = "/{id}/applicants/{appId}/{status}")
  @ResponseBody
  public BaseResponse setApplicantsStatus(@ApiParam("ID of entity")
                                          @PathVariable(value = "id") Long id,
                                          @ApiParam("Applicant status (one of 'accepted', 'declined', 'pending' 'interviewed', 'interview_scheduled')")
                                          @PathVariable(value = "status") String status,
                                          @ApiParam("ID of application to update")
                                          @PathVariable(value = "appId") Long appId,
                                          HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, BaseResponse.Status.DENIED, errors);
    }

    if (GroupApplication.ValidStatuses().indexOf(status) == -1) {
      errors.add("Invalid status!");
      return new GeneralResponse(response, BaseResponse.Status.BAD_DATA, errors);
    }

    UserToGroupPermission permission = getUserToGroupPermission(session.get().getUser(), getGroupRepository().findOne(id));
    boolean canUpdate = permission.canUpdate();
    if (!canUpdate) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new GeneralResponse(response, DENIED, errors);
    }

    GroupApplicantRepository groupApplicantRepository = getGroupApplicantRepository();
    GroupApplication applicationToSave = (GroupApplication) groupApplicantRepository.findOne(appId);
    if (applicationToSave.getStatus().equals(status)) {
      return new GeneralResponse(response, OK);
    }
    applicationToSave.setStatus(status);
    groupApplicantRepository.save(applicationToSave);

    ZonedDateTime now = ZonedDateTime.now();

    notificationController.markInvitationsAsDoneFor(applicationToSave);

    if (!status.equals("interview_scheduled")) {
      // Not sure why this is here, but im leaving it in
      List<Interview> interviews = interviewRepository.getAllByUserAndGroupTypeAndGroup(applicationToSave.getSender(), applicationToSave.getGroup().getGroupType(), applicationToSave.getGroup().getId());
      interviewHelper.setInterviewsAvailableAndSave(interviews);
    }

    switch (status) {
      case "declined":
        try {
          notificationController.sendApplicationRejectedNotification(applicationToSave);
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
          errors.add(e.getMessage());
          return new GeneralResponse(response, ERROR, errors);
        }
        break;
      case "interview_scheduled":
        T group = getGroupRepository().findOne(applicationToSave.getGroup().getId());

        I interviewInvitation = getInvitation();
        interviewInvitation.setGroup(group);
        interviewInvitation.setReceiver(applicationToSave.getSender());
        interviewInvitation.setType("interview");
        interviewInvitation.setApplicant(applicationToSave);
        errors.addAll(setInviteFieldsAndSave(interviewInvitation, session.get().getUser(), INVITED_TO_INTERVIEW, applicationToSave.getSender(), group));
        if (errors.size() > 0) {
          break;
        }

        interviewInvitation.setStatus(PENDING);

        interviewInvitation = getGroupInvitationRepository().save(interviewInvitation);
        try {
          notificationController.sendInterviewInvitation(interviewInvitation);
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
        }
        break;
      case "invited":
        I invitationToJoin = getInvitation();
        invitationToJoin.setGroup(getGroupRepository().findOne(id));
        invitationToJoin.setReceiver(userRepository.findOne(applicationToSave.getSender().getId()));
        invitationToJoin.setType("join");
        invitationToJoin.setApplicant(applicationToSave);

        Optional<Integer> role = getRoleFromInvitationType(invitationToJoin.getType());

        if (!role.isPresent()) {
          errors.add("Unrecognized type");
          return new GeneralResponse(response, BAD_DATA, errors);
        }

        errors = setInviteFieldsAndSave(invitationToJoin,
            session.get().getUser(),
            role.get(),
            applicationToSave.getSender(),
            getGroupRepository().findOne(applicationToSave.getGroup().getId()));

        if (errors.size() == 0) {
          try {
            notificationController.sendJoinInvitationNotification(invitationToJoin);
          } catch (Exception e) {
            logger.error(e.getMessage(), e);
          }
        }

        break;
      case "pending":
        break;
      default:
        return new GeneralResponse(response, BAD_DATA, "Invalid status");
    }

    if (errors.size() > 0) {
      return new GeneralResponse(response, ERROR, errors);
    }

    return new GeneralResponse(response, OK);
  }

  @GetMapping(path = "/{id}/can_edit")
  @ApiOperation("Returns whether or not the current user can edit the group")
  @ResponseBody
  protected GeneralResponse canEdit(
      @ApiParam("The id of the group to check against")
      @PathVariable(value = "id") Long id, @RequestBody T groupData, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }
    User user = session.get().getUser();
    T groupToSave = getGroupRepository().findOne(id);
    if (groupToSave == null || groupToSave.getDeleted()) {
      errors.add("Group does not exist");
      return new GeneralResponse(response, BAD_DATA, errors);
    }
    UserToGroupPermission permission = getUserToGroupPermission(user, groupToSave);
    boolean canUpdate = permission.canUpdate();
    if (!canUpdate) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new GeneralResponse(response, DENIED, errors);
    }
    return new GeneralResponse(response, BaseResponse.Status.OK);

  }

  @PostMapping(path = "/{id}/upload/thumbnail")
  @ResponseBody
  @ApiOperation(value = "Uploads a new thumbnail",
      notes = "Max file size is 128KB")
  public TypedResponse<UploadFile> uploadThumbnail(@PathVariable(value = "id") Long id, @RequestParam("file")
      MultipartFile fileToUpload, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, GeneralResponse.Status.DENIED, errors);
    }

    String fileType = fileToUpload.getContentType().split("/")[0];
    if (!fileType.equals("image")) {
      return new TypedResponse<>(response, ERROR, errors);
    }
    UploadFile uploadFile;
    try {
      uploadFile = fileController.saveFile(fileToUpload, "avatar", session.get().getUser());
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      return new TypedResponse<>(response, ERROR, errors);
    }

    T group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add("Group does not exist");
      return new TypedResponse<>(response, BAD_DATA, errors);
    }
    group.getProfile().setThumbnail_id(uploadFile.getId());
    getGroupApplicantRepository().save(group.getProfile());
    group.indexAsync();
    return new TypedResponse<>(response, OK, null, uploadFile);
  }

  @PostMapping(path = "/{id}/upload/background")
  @ResponseBody
  @ApiOperation(value = "Uploads a new background",
      notes = "Max file size is 128KB")
  public TypedResponse<UploadFile> uploadBackground(@PathVariable(value = "id") Long id, @RequestParam("file")
      MultipartFile fileToUpload, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, GeneralResponse.Status.DENIED, errors);
    }

    String fileType = fileToUpload.getContentType().split("/")[0];
    if (!fileType.equals("image")) {
      return new TypedResponse<>(response, BAD_DATA, errors);
    }
    UploadFile uploadFile;
    try {
      uploadFile = fileController.saveFile(fileToUpload, "background", session.get().getUser());
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      return new TypedResponse<>(response, BAD_DATA, e.getMessage());
    }

    T group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add("Group does not exist");
      return new TypedResponse<>(response, BAD_DATA, errors);
    }

    group.getProfile().setBackground_id(uploadFile.getId());
    getGroupApplicantRepository().save(group.getProfile());
    return new TypedResponse<>(response, OK, null, uploadFile);
  }

  @GetMapping(path = "/{id}/download/background")
  @ResponseBody
  @ApiOperation(value = "Download a background file")
  public TypedResponse<Long> downloadBackground(@PathVariable(value = "id") Long id, HttpServletRequest request, HttpServletResponse response) throws Exception {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, GeneralResponse.Status.DENIED, errors);
    }
    T group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add("Group does not exist");
      return new TypedResponse<>(response, BAD_DATA, errors);
    }
    long backgroundId = group.getProfile().getBackground_id();

    //TODO this appears to not be doing anything
    if (backgroundId == 0) {
      errors.add(FILE_NOT_FOUND);
      return new TypedResponse<>(response, GeneralResponse.Status.DENIED, errors);
    }
    return new TypedResponse<>(response, OK, null, backgroundId);
  }

  @GetMapping(path = "/{id}/download/thumbnail")
  @ResponseBody
  @ApiOperation(value = "Download a background file")
  public TypedResponse<Long> downloadThumbnail(@PathVariable(value = "id") Long id, HttpServletRequest request, HttpServletResponse response) throws Exception {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, GeneralResponse.Status.DENIED, errors);
    }
    T group = getGroupRepository().findOne(id);
    if (group == null || group.getDeleted()) {
      errors.add("Group does not exist");
      return new TypedResponse<>(response, BAD_DATA, errors);
    }
    long thumbnailId = group.getProfile().getThumbnail_id();
    if (thumbnailId == 0) {
      errors.add(FILE_NOT_FOUND);
      return new TypedResponse<>(response, GeneralResponse.Status.DENIED, errors);
    }
    return new TypedResponse<>(response, OK, null, thumbnailId);
  }

  private boolean validFieldsForCreate(T entity) {
    return entity.getName() != null;
  }

  protected boolean validFieldsForDelete(T entity) {
    return entity.getName() != null;
  }

  protected abstract GroupRepository<T> getGroupRepository();

  protected abstract GroupApplicantRepository getGroupApplicantRepository();

  protected abstract GroupProfile saveProfile(T group);

  protected abstract GroupMemberRepository<T, R> getRelationshipRepository();

  protected abstract UserToGroupPermission getUserToGroupPermission(User user, T group);

  protected abstract UserToGroupPermission<T> getUserToGroupPermissionTyped(User user, T group);

  protected abstract void removeRelationship(User user, T group, int role);

  protected abstract void addRelationship(User user, T group, int role);

  protected abstract void saveInvitation(I invitation);

  protected Session getSession() {
    return sessionFactory.openSession();
  }

  @SuppressWarnings("unchecked")
  private List<T> getGroupsWith(User owner, T group) {
    return getGroupRepository().getGroups(owner, group.getName());
  }

  private Set<User> getMembersOf(T group) {
    Set<User> users = new HashSet<>();
    Iterable<User> usersByGroup = getRelationshipRepository().getUsersByGroup(group);
    usersByGroup.forEach(users::add);
    return users;
  }

  private boolean isValidInterviewSlots(List<Interview> interviews, User user, long groupId) {
    LocalDateTime currentDateTime = ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime();

    T group = getGroupRepository().findOne(groupId);
    if (group == null || group.getDeleted()) {
      return false;
    }

    UserToGroupPermission permission = getUserToGroupPermission(user, group);
    return InterviewUtil.isValidInterviewSlots(interviews, currentDateTime, permission);
  }

  protected BaseResponse grantAccessForMember(Long id, Long memberId, int access, HttpServletResponse response, HttpServletRequest request) {
    T group = getGroupRepository().findOne(id);
    List<String> errors = new ArrayList<>();
    if (group == null || group.getDeleted()) {
      errors.add("Group does not exist");
      return new TypedResponse<>(response, BAD_DATA, errors);
    }

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, DENIED, errors);
    }

    User curUser = session.get().getUser();
    UserToGroupPermission curPermissions = getUserToGroupPermission(curUser, group);

    if (!curPermissions.canUpdate()) {
      errors.add("Permission denied");
      return new TypedResponse<>(response, DENIED, errors);
    }

    if (group == null) {
      errors.add("Group not found");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    Optional<User> userOptional = new ArrayList<>(getMembersOf(group))
        .stream()
        .filter(u -> u.getId().equals(memberId))
        .limit(1)
        .reduce((a, u) -> u);

    if (!userOptional.isPresent()) {
      errors.add("User is not part of the group!");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User user = userOptional.get();
    UserToGroupPermission permission = getUserToGroupPermission(user, group);

    if (permission.hasRole(access)) {
      return new GeneralResponse(response, OK);
    }
    addRelationship(user, group, access);
    return new GeneralResponse(response, OK);
  }

  BaseResponse revokeAccessForMember(Long id, Long memberId, int access, HttpServletResponse response, HttpServletRequest request) {
    T group = getGroupRepository().findOne(id);
    List<String> errors = new ArrayList<>();
    if (group == null || group.getDeleted()) {
      errors.add("Group does not exist");
      return new TypedResponse<>(response, BAD_DATA, errors);
    }

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, DENIED, errors);
    }

    User curUser = session.get().getUser();
    UserToGroupPermission curPermissions = getUserToGroupPermission(curUser, group);

    if (!curPermissions.canUpdate()) {
      errors.add("Permission denied");
      return new TypedResponse<>(response, DENIED, errors);
    }

    if (group == null) {
      errors.add("Group not found");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    Optional<User> userOptional = new ArrayList<>(getMembersOf(group))
        .stream()
        .filter(u -> u.getId().equals(memberId))
        .limit(1)
        .reduce((a, u) -> u);

    if (!userOptional.isPresent()) {
      errors.add("User is not part of the group!");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User user = userOptional.get();
    UserToGroupPermission permission = getUserToGroupPermission(user, group);

    if (!permission.hasRole(access)) {
      return new GeneralResponse(response, OK);
    }
    removeRelationship(user, group, access);
    return new GeneralResponse(response, OK);
  }

  private BaseResponse kickMember(Long id, Long memberId, int access, HttpServletResponse response, HttpServletRequest request) {
    T group = getGroupRepository().findOne(id);
    List<String> errors = new ArrayList<>();
    if (group == null || group.getDeleted()) {
      errors.add("Group does not exist");
      return new TypedResponse<>(response, BAD_DATA, errors);
    }
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new TypedResponse<>(response, DENIED, errors);
    }

    User curUser = session.get().getUser();
    UserToGroupPermission curPermissions = getUserToGroupPermission(curUser, group);

    if (!curPermissions.canUpdate()) {
      errors.add("Permission denied");
      return new TypedResponse<>(response, DENIED, errors);
    }

    if (group == null) {
      errors.add("Group not found");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    Optional<User> userOptional = new ArrayList<>(getMembersOf(group))
        .stream()
        .filter(u -> u.getId().equals(memberId))
        .limit(1)
        .reduce((a, u) -> u);

    if (!userOptional.isPresent()) {
      errors.add("User is not part of the group!");
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User user = userOptional.get();
    UserToGroupPermission<T> permission = getUserToGroupPermissionTyped(user, group);

    Iterable<Integer> roles = permission.getRoles();
    roles.forEach(integer -> removeRelationship(user, group, integer));

    return new GeneralResponse(response, OK);
  }

  protected T setJoinPermissions(User user, T group) {
    UserToGroupPermission<T> permission = getUserToGroupPermissionTyped(user, group);
    genericSetJoinPermissions(user, group, permission);
    return group;
  }

  protected abstract GroupApplication<T> getApplication();

  protected abstract I getInvitation();

  protected abstract GroupInvitationRepository<I> getGroupInvitationRepository();

  protected abstract PossibleError validateGroup(User user, T group);

  protected abstract void createInterviewTemplate(T group);
}
