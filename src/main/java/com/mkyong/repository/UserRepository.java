package com.mkyong.repository;


import com.mkyong.model.Userr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRepository extends JpaRepository<Userr, Long> {

    List<Userr> findByName(String title);
    /*
 Custom query
    @Query("SELECT b FROM Userr b WHERE b.createdAt > :date")
    List<Userr> findByCreatedAfterDate(@Param("date") LocalDate date);
*/

}
