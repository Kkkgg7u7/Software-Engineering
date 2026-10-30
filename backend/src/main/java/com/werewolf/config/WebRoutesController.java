package com.werewolf.config;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
/** Optional single-JAR frontend; the API retains independent authentication. */
@Controller public class WebRoutesController {
 @GetMapping({"/login","/chat","/games","/wall","/friends","/me","/records","/lobby","/room/{code}","/game/{code}","/join/{code}"})
 public String app(){return "forward:/index.html";}
}
