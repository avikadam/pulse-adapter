DROP DATABASE IF EXISTS `pulse`;
CREATE DATABASE `pulse` CHARACTER SET=ascii;

CREATE OR REPLACE TABLE `pulse`.`provider` (
  `id` int(10) unsigned NOT NULL auto_increment,
  `timestamp` datetime(3) NOT NULL,
  `ric` varchar(32) NOT NULL,
  `ARB_GAPOUT` bigint unsigned, -- uint64/5
  `TTL_GAPOUT` bigint unsigned, -- uint64/5
  `DDS_DSO_ID` smallint unsigned, -- uint32/2, can be null
  `SPS_PROV` varchar(255), -- RMTES string
  `DUDT_RIC` varchar(32), -- ASCII string
  `SPS_DESCR` varchar(255), -- RMTES string
  `SPS_TME_MS` time(3), -- uint64/4, milliseconds past midnight.
  `SPS_FREQ` smallint unsigned, -- uint32/2
  `SPS_FD_STS` enum('TCPD','UDPL','UNDF','TCPO','UDPU','UDPN','UDPS','UDPD','UNMO'),
  `ARB_GAP_PD` bigint unsigned, -- uint64/5
  `SPS_GP_DSC` enum('Msg','Frm'),
  `SPS_SVC_TM` time, -- uint64/5
  `SPS_FAIL_T` tinyint unsigned, -- uint32/1
  `SPS_PV_STS` enum('UP','DOWN','UNAV'),
  `SPS_SP_RIC` varchar(32), -- ASCII string
  `AC_RD_SV_I` enum('A','B','C','D'),
  `SP_INMAP` varchar(32), -- ASCII string, can be null
  `L_H_STAT` enum('OPEN'),
  `SPS_STYPE` enum('CHE','CVA','ITDG','MCU','EFX','EVAI','PPDS','IA-TMS','DPOP-TMS','TOP','CDS'),
  PRIMARY KEY (`id`),
  KEY `ric_timestamp` (`ric`, `timestamp`)
) ENGINE=TokuDB CHARACTER SET=ascii COMPRESSION=TOKUDB_LZMA;

CREATE OR REPLACE TABLE `pulse`.`venue` (
  `id` int(10) unsigned NOT NULL auto_increment,
  `timestamp` datetime(3) NOT NULL,
  `ric` varchar(32) NOT NULL,
  `SRC_HB_TM` time, -- time/5
  `AL_UPD_TM` time, -- time/5
  `SRC_HB_DT` date, -- date/4, can be null
  `AL_UPD_DT` date, -- date/4
  `SRC_HB_CYC` bigint unsigned, -- uint64/5, can be null
  `MSG_CNT_I` bigint unsigned, -- uint64/5, can be null
  `SRC_HB_TZ2` time, -- RMTES string for timezone offset
  `SRC_HB_DSC` varchar(80), -- RMTES string
  `SRC_HB_STS` enum('Overdue','Cmpliant','Not Expd'),
  `SRC_HB_TSO` enum('3rd Prty','TR'),
  `SRC_HB_TYP` enum('Cont','Idl-Poll'),
  PRIMARY KEY (`id`),
  KEY `ric_timestamp` (`ric`, `timestamp`)
) ENGINE=TokuDB CHARACTER SET=ascii COMPRESSION=TOKUDB_LZMA;

DELIMITER $$

DROP PROCEDURE IF EXISTS `pulse`.`sp_get_table_size`$$
CREATE PROCEDURE `pulse`.`sp_get_table_size` ()
READS SQL DATA
SQL SECURITY INVOKER
BEGIN
SELECT
    table_name,
    CONCAT(FORMAT(DAT/POWER(1024,pw1),2),' ',SUBSTR(units,pw1*2+1,2)) DATSIZE,
    CONCAT(FORMAT(NDX/POWER(1024,pw2),2),' ',SUBSTR(units,pw2*2+1,2)) NDXSIZE,
    CONCAT(FORMAT(TBL/POWER(1024,pw3),2),' ',SUBSTR(units,pw3*2+1,2)) TBLSIZE
FROM
(
    SELECT table_name,DAT,NDX,TBL,IF(px>4,4,px) pw1,IF(py>4,4,py) pw2,IF(pz>4,4,pz) pw3
    FROM
    (
        SELECT table_name,data_length DAT,index_length NDX,data_length+index_length TBL,
        FLOOR(LOG(IF(data_length=0,1,data_length))/LOG(1024)) px,
        FLOOR(LOG(IF(index_length=0,1,index_length))/LOG(1024)) py,
        FLOOR(LOG(IF(data_length+index_length=0,1,data_length+index_length))/LOG(1024)) pz
        FROM information_schema.tables
        WHERE table_schema='pulse'
    ) AA
) A,(SELECT 'B KBMBGBTB' units) B;
END$$

DELIMITER ;

