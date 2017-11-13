package server.controllers.rest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import server.controllers.SessionController;
import server.controllers.rest.response.GeneralResponse;
import server.entities.UserPermission;
import server.entities.dto.FuseSession;
import server.entities.dto.User;
import server.repositories.UserRepository;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping(value = "/user")
public class UserController {

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private SessionController sessionController;

  @PostMapping(path = "/add")
  @ResponseBody
  public GeneralResponse addNewUser(@RequestBody User user, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();

    if (user != null) {
      if (user.getName() == null)
        errors.add("Missing Name");
      if (user.getEncoded_password() == null)
        errors.add("Missing Password");
      if (user.getEmail() == null)
        errors.add("Missing Email");
      if (errors.size() == 0 && userRepository.findByEmail(user.getEmail()) != null)
        errors.add("Username already exists!");
    } else
      errors.add("No request body found");

    if (errors.size() == 0)
      userRepository.save(user);

    return new GeneralResponse(response, errors);
  }

  @PostMapping(path = "/login")
  @ResponseBody
  public GeneralResponse login(@RequestBody User user, HttpServletRequest request, HttpServletResponse response) {

    logoutIfLoggedIn(user, request);

    List<String> errors = new ArrayList<>();
    if (user == null) {
      errors.add("Invalid Credentials");
    } else {
      User dbUser = userRepository.findByEmail(user.getEmail());

      if (dbUser == null) {
        errors.add("Invalid Credentials");
      } else {
        user.setEncoded_password(dbUser.getEncoded_password());

        if (user.checkPassword()) {
          return new GeneralResponse(response, GeneralResponse.Status.OK, null, sessionController.createSession(dbUser));
        }
        errors.add("Invalid Credentials");
      }
    }

    return new GeneralResponse(response, GeneralResponse.Status.DENIED, errors);
  }

  @PostMapping(path = "/logout")
  @ResponseBody
  public GeneralResponse logout(HttpServletRequest request, HttpServletResponse response) {
    Optional<FuseSession> session = sessionController.getSession(request);
    if (session.isPresent()) {
      sessionController.deleteSession(session.get());
      return new GeneralResponse(response, GeneralResponse.Status.OK);
    } else {
      List<String> errors = new ArrayList<>();
      errors.add("No active session");
      return new GeneralResponse(response, GeneralResponse.Status.ERROR, errors);
    }
  }

  @GetMapping(path = "/userbyID")
  @ResponseBody
  public GeneralResponse getUserbyID(long id, HttpServletResponse response) {
    return new GeneralResponse(response, GeneralResponse.Status.OK, null, userRepository.findOne(id));
  }

  @GetMapping(path = "/all")
  @ResponseBody
  public GeneralResponse getAllUsers(HttpServletResponse response) {
    return new GeneralResponse(response, GeneralResponse.Status.OK, null, userRepository.findAll());
  }

  private boolean logoutIfLoggedIn(User user, HttpServletRequest request) {
    UserPermission userPermission = new UserPermission(user, request, sessionController);
    if (userPermission.isLoggedIn()) {
      Optional<FuseSession> session = sessionController.getSession(request);
      session.ifPresent(s -> sessionController.deleteSession(s));
      return true;
    } else {
      return false;
    }
  }
}
